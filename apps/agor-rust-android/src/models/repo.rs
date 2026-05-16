use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Repo {
    pub repo_id: String,
    pub name: String,
    pub url: Option<String>,
    pub default_branch: Option<String>,
    pub path: Option<String>,
}
