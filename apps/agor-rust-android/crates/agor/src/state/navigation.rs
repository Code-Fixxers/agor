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
                    && !s.is_scheduled()
                    && (s.ready_for_prompt.unwrap_or(false)
                        || matches!(s.status, SessionStatus::Running)
                        || self.favorites.contains(&s.session_id)
                        || s.has_explicit_title())
            })
            .collect();

        important.sort_by(|a, b| b.last_updated.cmp(&a.last_updated));
        important.truncate(10);
        important
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

        let attention = self.attention_sessions();
        if !attention.is_empty() {
            rows.push(SidebarRow::SectionHeader {
                label: "NEEDS ATTENTION".to_string(),
            });
            for s in &attention {
                rows.push(SidebarRow::SessionRow {
                    session: (*s).clone(),
                    depth: 0,
                    is_favorite: self.favorites.contains(&s.session_id),
                });
            }
        }

        let important = self.important_sessions();
        if !important.is_empty() {
            rows.push(SidebarRow::SectionHeader {
                label: "IMPORTANT".to_string(),
            });
            for s in &important {
                rows.push(SidebarRow::SessionRow {
                    session: (*s).clone(),
                    depth: 0,
                    is_favorite: self.favorites.contains(&s.session_id),
                });
            }
        }

        for board in &self.boards {
            if board.archived.unwrap_or(false) {
                continue;
            }

            let _board_expanded = !self.expanded_boards.contains(&board.board_id)
                || self.expanded_boards.contains(&board.board_id);

            rows.push(SidebarRow::BoardHeader {
                board: board.clone(),
                expanded: !self
                    .expanded_boards
                    .contains(&board.board_id),
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

                    let wt_collapsed = self
                        .expanded_worktrees
                        .contains(&wt.worktree_id);

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

    nav.repos_by_id = repos
        .into_iter()
        .map(|r| (r.repo_id.clone(), r))
        .collect();

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
