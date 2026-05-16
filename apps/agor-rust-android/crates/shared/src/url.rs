pub fn normalize_agor_base_url(raw: &str) -> String {
    let mut s = raw.trim().to_string();

    if s.ends_with('/') {
        s.pop();
    }

    if let Some(idx) = s.find("/ui") {
        s.truncate(idx);
    }

    if !s.starts_with("http://") && !s.starts_with("https://") {
        s = format!("http://{s}");
    }

    if s.starts_with("http://") {
        if let Ok(parsed) = url::Url::parse(&s) {
            if parsed.port().is_none() {
                let host = parsed.host_str().unwrap_or("localhost");
                s = format!("http://{host}:3030");
            }
        }
    }

    s
}

pub fn agor_base_url_candidates(raw: &str) -> Vec<String> {
    let normalized = normalize_agor_base_url(raw);
    let mut candidates = vec![normalized.clone()];

    if normalized.starts_with("http://") {
        let https = normalized.replacen("http://", "https://", 1);
        let https_no_port = https
            .strip_suffix(":3030")
            .map(|s| s.to_string())
            .unwrap_or_else(|| https.clone());

        if https_no_port != https {
            candidates.push(https_no_port);
        }
        candidates.push(https);
    }

    candidates
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_normalize_plain_host() {
        assert_eq!(normalize_agor_base_url("myserver"), "http://myserver:3030");
    }

    #[test]
    fn test_normalize_with_port() {
        assert_eq!(
            normalize_agor_base_url("http://myserver:4000"),
            "http://myserver:4000"
        );
    }

    #[test]
    fn test_normalize_https() {
        assert_eq!(
            normalize_agor_base_url("https://agor.example.com"),
            "https://agor.example.com"
        );
    }

    #[test]
    fn test_normalize_strip_ui() {
        assert_eq!(
            normalize_agor_base_url("http://localhost:3030/ui"),
            "http://localhost:3030"
        );
    }

    #[test]
    fn test_candidates() {
        let c = agor_base_url_candidates("myserver");
        assert!(c.len() >= 2);
        assert_eq!(c[0], "http://myserver:3030");
    }
}
