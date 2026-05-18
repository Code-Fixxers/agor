use std::collections::{HashMap, HashSet};

use crate::models::*;
use crate::network::agor_client::AgorClient;
use agor_shared::logger::{AppLogger, LogCategory};

#[derive(Debug, Clone, PartialEq)]
pub enum SidebarRow {
    BoardHeader {
        board: Board,
        expanded: bool,
    },
    WorktreeRow {
        worktree: Worktree,
        repo_name: String,
        expanded: bool,
    },
    SessionRow {
        session: Session,
        depth: u8,
        is_favorite: bool,
    },
    SectionHeader {
        label: String,
    },
    OlderTasksRow {
        count: usize,
    },
}

#[derive(Debug, Clone)]
pub struct NavStore {
    pub boards: Vec<Board>,
    pub worktrees_by_board: HashMap<String, Vec<Worktree>>,
    pub repos_by_id: HashMap<String, Repo>,
    pub sessions: Vec<Session>,
    pub sessions_by_worktree: HashMap<String, Vec<Session>>,
    pub favorites: HashSet<String>,
    pub search_query: String,
    pub is_loading: bool,
    pub error: Option<String>,
    pub expanded_boards: HashSet<String>,
    pub expanded_worktrees: HashSet<String>,
}

impl NavStore {
    pub fn new() -> Self {
        Self {
            boards: Vec::new(),
            worktrees_by_board: HashMap::new(),
            repos_by_id: HashMap::new(),
            sessions: Vec::new(),
            sessions_by_worktree: HashMap::new(),
            favorites: HashSet::new(),
            search_query: String::new(),
            is_loading: false,
            error: None,
            expanded_boards: HashSet::new(),
            expanded_worktrees: HashSet::new(),
        }
    }

    pub fn attention_sessions(&self) -> Vec<&Session> {
        self.sessions
            .iter()
            .filter(|s| s.status.needs_attention() && !s.is_scheduled())
            .collect()
    }

    pub fn favorite_sessions(&self) -> Vec<&Session> {
        let mut favorites: Vec<&Session> = self
            .sessions
            .iter()
            .filter(|s| self.favorites.contains(&s.session_id) && !s.is_scheduled())
            .collect();

        favorites.sort_by(|a, b| b.last_updated.cmp(&a.last_updated));
        favorites
    }

    pub fn important_sessions(&self) -> Vec<&Session> {
        let attention_ids: HashSet<&str> = self
            .attention_sessions()
            .iter()
            .map(|s| s.session_id.as_str())
            .collect();

        let mut important: Vec<&Session> = self
            .sessions
            .iter()
            .filter(|s| {
                !attention_ids.contains(s.session_id.as_str())
                    && !self.favorites.contains(&s.session_id)
                    && !s.is_scheduled()
                    && (s.ready_for_prompt.unwrap_or(false)
                        || matches!(s.status, SessionStatus::Running)
                        || s.has_explicit_title())
            })
            .collect();

        important.sort_by(|a, b| b.last_updated.cmp(&a.last_updated));
        important.truncate(10);
        important
    }

    fn build_hierarchical_session_rows<'a>(
        &self,
        sessions: impl IntoIterator<Item = &'a Session>,
    ) -> Vec<SidebarRow> {
        let session_ids: HashSet<&str> = sessions
            .into_iter()
            .map(|session| session.session_id.as_str())
            .collect();

        let mut rows = Vec::new();
        if session_ids.is_empty() {
            return rows;
        }

        for board in &self.boards {
            if board.archived.unwrap_or(false) {
                continue;
            }

            let Some(worktrees) = self.worktrees_by_board.get(&board.board_id) else {
                continue;
            };

            let board_start = rows.len();
            rows.push(SidebarRow::BoardHeader {
                board: board.clone(),
                expanded: true,
            });

            for wt in worktrees {
                if wt.archived.unwrap_or(false) {
                    continue;
                }

                let Some(worktree_sessions) = self.sessions_by_worktree.get(&wt.worktree_id) else {
                    continue;
                };

                let matching_sessions: Vec<&Session> = worktree_sessions
                    .iter()
                    .filter(|session| {
                        session_ids.contains(session.session_id.as_str())
                            && !session.archived.unwrap_or(false)
                    })
                    .collect();

                if matching_sessions.is_empty() {
                    continue;
                }

                let repo_name = self
                    .repos_by_id
                    .get(&wt.repo_id)
                    .map(|r| r.name.clone())
                    .unwrap_or_default();

                rows.push(SidebarRow::WorktreeRow {
                    worktree: wt.clone(),
                    repo_name,
                    expanded: true,
                });

                for session in matching_sessions {
                    rows.push(SidebarRow::SessionRow {
                        session: session.clone(),
                        depth: 2,
                        is_favorite: self.favorites.contains(&session.session_id),
                    });
                }
            }

            if rows.len() == board_start + 1 {
                rows.truncate(board_start);
            }
        }

        rows
    }

    pub fn search_results(&self) -> Vec<&Session> {
        if self.search_query.is_empty() {
            return Vec::new();
        }
        let q = self.search_query.to_lowercase();
        self.sessions
            .iter()
            .filter(|s| s.display_title().to_lowercase().contains(&q))
            .take(20)
            .collect()
    }

    pub fn build_sidebar_rows(&self) -> Vec<SidebarRow> {
        let mut rows = Vec::new();

        let favorites = self.favorite_sessions();
        if !favorites.is_empty() {
            rows.push(SidebarRow::SectionHeader {
                label: "FAVOURITES".to_string(),
            });
            for s in &favorites {
                rows.push(SidebarRow::SessionRow {
                    session: (*s).clone(),
                    depth: 0,
                    is_favorite: true,
                });
            }
        }

        let attention = self.attention_sessions();
        if !attention.is_empty() {
            rows.push(SidebarRow::SectionHeader {
                label: "NEEDS ATTENTION".to_string(),
            });
            rows.extend(self.build_hierarchical_session_rows(attention));
        }

        let important = self.important_sessions();
        if !important.is_empty() {
            rows.push(SidebarRow::SectionHeader {
                label: "IMPORTANT".to_string(),
            });
            rows.extend(self.build_hierarchical_session_rows(important));
        }

        for board in &self.boards {
            if board.archived.unwrap_or(false) {
                continue;
            }

            let _board_expanded = !self.expanded_boards.contains(&board.board_id)
                || self.expanded_boards.contains(&board.board_id);

            rows.push(SidebarRow::BoardHeader {
                board: board.clone(),
                expanded: !self.expanded_boards.contains(&board.board_id),
            });

            if self.expanded_boards.contains(&board.board_id) {
                continue;
            }

            if let Some(worktrees) = self.worktrees_by_board.get(&board.board_id) {
                for wt in worktrees {
                    if wt.archived.unwrap_or(false) {
                        continue;
                    }

                    let repo_name = self
                        .repos_by_id
                        .get(&wt.repo_id)
                        .map(|r| r.name.clone())
                        .unwrap_or_default();

                    let wt_collapsed = self.expanded_worktrees.contains(&wt.worktree_id);

                    rows.push(SidebarRow::WorktreeRow {
                        worktree: wt.clone(),
                        repo_name,
                        expanded: !wt_collapsed,
                    });

                    if wt_collapsed {
                        continue;
                    }

                    if let Some(sessions) = self.sessions_by_worktree.get(&wt.worktree_id) {
                        for s in sessions {
                            if s.archived.unwrap_or(false) {
                                continue;
                            }
                            rows.push(SidebarRow::SessionRow {
                                session: s.clone(),
                                depth: 2,
                                is_favorite: self.favorites.contains(&s.session_id),
                            });
                        }
                    }
                }
            }
        }

        rows
    }
}

pub async fn refresh_navigation(
    client: &AgorClient,
    nav: &mut NavStore,
    logger: &AppLogger,
) -> Result<(), String> {
    nav.is_loading = true;
    nav.error = None;

    logger.info(LogCategory::Nav, "Refreshing navigation data...");

    let (boards_result, sessions_result, repos_result) = tokio::join!(
        client.list_boards(),
        client.list_sessions(false, Some(200)),
        client.list_repos(),
    );

    let boards = boards_result.map_err(|e| e.to_string())?;
    let sessions = sessions_result.map_err(|e| e.to_string())?;
    let repos = repos_result.map_err(|e| e.to_string())?;

    nav.repos_by_id = repos.into_iter().map(|r| (r.repo_id.clone(), r)).collect();

    nav.sessions_by_worktree.clear();
    for session in &sessions {
        nav.sessions_by_worktree
            .entry(session.worktree_id.clone())
            .or_default()
            .push(session.clone());
    }

    for sessions in nav.sessions_by_worktree.values_mut() {
        sessions.sort_by(|a, b| b.last_updated.cmp(&a.last_updated));
    }

    let mut all_worktrees = Vec::new();
    nav.worktrees_by_board.clear();

    for board in &boards {
        match client.list_worktrees(Some(&board.board_id)).await {
            Ok(wts) => {
                nav.worktrees_by_board
                    .insert(board.board_id.clone(), wts.clone());
                all_worktrees.extend(wts);
            }
            Err(e) => {
                logger.error(
                    LogCategory::Nav,
                    format!("Failed to load worktrees for board {}: {e}", board.name),
                );
            }
        }
    }

    nav.boards = boards;
    nav.sessions = sessions;
    nav.is_loading = false;

    logger.info(
        LogCategory::Nav,
        format!(
            "Loaded {} boards, {} worktrees, {} sessions",
            nav.boards.len(),
            all_worktrees.len(),
            nav.sessions.len(),
        ),
    );

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::session::AgenticTool;

    fn board(id: &str, name: &str) -> Board {
        Board {
            board_id: id.to_string(),
            name: name.to_string(),
            description: None,
            emoji: None,
            color: None,
            created_at: None,
            created_by: None,
            archived: Some(false),
        }
    }

    fn worktree(id: &str, board_id: &str, name: &str) -> Worktree {
        Worktree {
            worktree_id: id.to_string(),
            repo_id: "repo-1".to_string(),
            board_id: Some(board_id.to_string()),
            name: name.to_string(),
            branch: None,
            path: None,
            status: None,
            created_at: None,
            created_by: None,
            archived: Some(false),
            archived_reason: None,
            others_can: None,
        }
    }

    fn session(
        id: &str,
        worktree_id: &str,
        title: Option<&str>,
        status: SessionStatus,
        last_updated: &str,
    ) -> Session {
        Session {
            session_id: id.to_string(),
            agentic_tool: AgenticTool::Codex,
            agentic_tool_version: None,
            sdk_session_id: None,
            status,
            created_at: "2026-05-18T08:00:00.000Z".to_string(),
            last_updated: last_updated.to_string(),
            created_by: "user-1".to_string(),
            unix_username: None,
            worktree_id: worktree_id.to_string(),
            worktree_board_id: Some("board-1".to_string()),
            url: None,
            git_state: None,
            genealogy: None,
            tasks: None,
            message_count: None,
            title: title.map(str::to_string),
            description: None,
            permission_config: None,
            model_config: None,
            current_context_usage: None,
            context_window_limit: None,
            scheduled_from_worktree: Some(false),
            ready_for_prompt: Some(false),
            archived: Some(false),
            archived_reason: None,
        }
    }

    fn nav_with_sessions(sessions: Vec<Session>) -> NavStore {
        let mut nav = NavStore::new();
        nav.boards = vec![board("board-1", "Board One")];
        nav.worktrees_by_board.insert(
            "board-1".to_string(),
            vec![worktree("worktree-1", "board-1", "Worktree One")],
        );
        nav.sessions = sessions.clone();
        nav.sessions_by_worktree
            .insert("worktree-1".to_string(), sessions);
        nav
    }

    #[test]
    fn favorite_sessions_are_flat_at_the_top() {
        let favorite = session(
            "favorite",
            "worktree-1",
            Some("Favorite Session"),
            SessionStatus::Idle,
            "2026-05-18T08:03:00.000Z",
        );
        let important = session(
            "important",
            "worktree-1",
            Some("Important Session"),
            SessionStatus::Idle,
            "2026-05-18T08:02:00.000Z",
        );
        let mut nav = nav_with_sessions(vec![favorite, important]);
        nav.favorites.insert("favorite".to_string());

        let rows = nav.build_sidebar_rows();

        assert!(matches!(
            &rows[0],
            SidebarRow::SectionHeader { label } if label == "FAVOURITES"
        ));
        assert!(matches!(
            &rows[1],
            SidebarRow::SessionRow {
                session,
                depth: 0,
                is_favorite: true,
            } if session.session_id == "favorite"
        ));
    }

    #[test]
    fn important_sessions_keep_board_and_worktree_context() {
        let favorite = session(
            "favorite",
            "worktree-1",
            Some("Favorite Session"),
            SessionStatus::Idle,
            "2026-05-18T08:03:00.000Z",
        );
        let important = session(
            "important",
            "worktree-1",
            Some("Important Session"),
            SessionStatus::Idle,
            "2026-05-18T08:02:00.000Z",
        );
        let mut nav = nav_with_sessions(vec![favorite, important]);
        nav.favorites.insert("favorite".to_string());

        let rows = nav.build_sidebar_rows();
        let important_index = rows
            .iter()
            .position(
                |row| matches!(row, SidebarRow::SectionHeader { label } if label == "IMPORTANT"),
            )
            .expect("important section exists");

        assert!(matches!(
            &rows[important_index + 1],
            SidebarRow::BoardHeader { board, .. } if board.board_id == "board-1"
        ));
        assert!(matches!(
            &rows[important_index + 2],
            SidebarRow::WorktreeRow { worktree, .. } if worktree.worktree_id == "worktree-1"
        ));
        assert!(matches!(
            &rows[important_index + 3],
            SidebarRow::SessionRow {
                session,
                depth: 2,
                is_favorite: false,
            } if session.session_id == "important"
        ));
    }

    #[test]
    fn full_board_tree_still_contains_all_sessions() {
        let favorite = session(
            "favorite",
            "worktree-1",
            Some("Favorite Session"),
            SessionStatus::Idle,
            "2026-05-18T08:03:00.000Z",
        );
        let normal = session(
            "normal",
            "worktree-1",
            None,
            SessionStatus::Idle,
            "2026-05-18T08:01:00.000Z",
        );
        let mut nav = nav_with_sessions(vec![favorite, normal]);
        nav.favorites.insert("favorite".to_string());

        let rows = nav.build_sidebar_rows();

        assert!(rows.iter().any(|row| matches!(
            row,
            SidebarRow::SessionRow {
                session,
                depth: 2,
                is_favorite: true,
            } if session.session_id == "favorite"
        )));
        assert!(rows.iter().any(|row| matches!(
            row,
            SidebarRow::SessionRow {
                session,
                depth: 2,
                is_favorite: false,
            } if session.session_id == "normal"
        )));
    }
}
