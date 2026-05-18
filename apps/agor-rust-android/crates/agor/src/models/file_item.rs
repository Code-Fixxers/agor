use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileListItem {
    pub path: String,
    pub size: Option<i64>,
    pub is_directory: Option<bool>,
    pub modified_at: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileDetail {
    pub path: String,
    pub content: Option<String>,
    pub base64: Option<String>,
    pub media_type: Option<String>,
    pub size: Option<i64>,
    pub truncated: Option<bool>,
}

impl FileDetail {
    pub fn file_name(&self) -> &str {
        self.path.rsplit('/').next().unwrap_or(&self.path)
    }

    pub fn is_image(&self) -> bool {
        if let Some(mt) = &self.media_type {
            return mt.starts_with("image/");
        }
        let lower = self.path.to_lowercase();
        lower.ends_with(".png")
            || lower.ends_with(".jpg")
            || lower.ends_with(".jpeg")
            || lower.ends_with(".gif")
            || lower.ends_with(".webp")
            || lower.ends_with(".svg")
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct VirtualNode {
    pub name: String,
    pub path: String,
    pub is_directory: bool,
    pub children: Vec<VirtualNode>,
    pub size: Option<i64>,
}

impl VirtualNode {
    pub fn build_tree(items: &[FileListItem]) -> Vec<VirtualNode> {
        let mut root_children: Vec<VirtualNode> = Vec::new();

        for item in items {
            let parts: Vec<&str> = item.path.split('/').filter(|p| !p.is_empty()).collect();
            insert_path(&mut root_children, &parts, 0, item);
        }

        sort_nodes(&mut root_children);
        root_children
    }
}

fn insert_path(children: &mut Vec<VirtualNode>, parts: &[&str], depth: usize, item: &FileListItem) {
    if depth >= parts.len() {
        return;
    }

    let name = parts[depth].to_string();
    let is_leaf = depth == parts.len() - 1;
    let path = parts[..=depth].join("/");

    let existing = children.iter_mut().find(|c| c.name == name);

    if let Some(node) = existing {
        if !is_leaf {
            insert_path(&mut node.children, parts, depth + 1, item);
        }
    } else if is_leaf {
        children.push(VirtualNode {
            name,
            path,
            is_directory: item.is_directory.unwrap_or(false),
            children: Vec::new(),
            size: item.size,
        });
    } else {
        let mut dir = VirtualNode {
            name,
            path,
            is_directory: true,
            children: Vec::new(),
            size: None,
        };
        insert_path(&mut dir.children, parts, depth + 1, item);
        children.push(dir);
    }
}

fn sort_nodes(nodes: &mut [VirtualNode]) {
    nodes.sort_by(|a, b| {
        b.is_directory
            .cmp(&a.is_directory)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    for node in nodes.iter_mut() {
        sort_nodes(&mut node.children);
    }
}
