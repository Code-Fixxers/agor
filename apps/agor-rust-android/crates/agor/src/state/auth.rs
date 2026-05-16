use crate::models::user::User;
use crate::network::agor_client::AgorClient;
use crate::state::storage::AppStorage;
use crate::models::server_profile::{ProfileCredentials, ServerProfile};
use agor_shared::logger::{AppLogger, LogCategory};

#[derive(Debug, Clone, PartialEq)]
pub enum AuthState {
    Unknown,
    NeedsLogin,
    Authenticated { user: User },
}

#[derive(Debug, Clone)]
pub struct AuthStore {
    pub state: AuthState,
    pub user: Option<User>,
    pub error: Option<String>,
}

impl AuthStore {
    pub fn new() -> Self {
        Self {
            state: AuthState::Unknown,
            user: None,
            error: None,
        }
    }
}

pub async fn bootstrap(
    client: &AgorClient,
    storage: &AppStorage,
    logger: &AppLogger,
) -> AuthState {
    logger.info(LogCategory::Auth, "Bootstrapping auth...");

    let profile = storage
        .active_profile()
        .or_else(|| storage.default_profile());

    let profile = match profile {
        Some(p) => p.clone(),
        None => {
            logger.info(LogCategory::Auth, "No saved profile, needs login");
            return AuthState::NeedsLogin;
        }
    };

    client.set_base_url(&profile.url);

    if let Some(creds) = storage.credentials.get(&profile.id) {
        if let Some(token) = &creds.access_token {
            let mut tokens = client.tokens.write().unwrap();
            tokens.access_token = Some(token.clone());
            tokens.refresh_token = creds.refresh_token.clone();
            tokens.server_url = Some(profile.url.clone());
            tokens.user_id = creds.user_id.clone();
            tokens.last_email = creds.user_email.clone();
            drop(tokens);

            match client.me().await {
                Ok(user) => {
                    logger.info(LogCategory::Auth, format!("Restored session for {}", user.name));
                    return AuthState::Authenticated { user };
                }
                Err(e) => {
                    logger.info(LogCategory::Auth, format!("Token expired, trying refresh: {e}"));
                    match client.refresh_token().await {
                        Ok(_) => {
                            if let Ok(user) = client.me().await {
                                logger.info(LogCategory::Auth, "Token refreshed successfully");
                                return AuthState::Authenticated { user };
                            }
                        }
                        Err(e2) => {
                            logger.error(LogCategory::Auth, format!("Refresh failed: {e2}"));
                        }
                    }

                    if let Some(password) = &creds.saved_password {
                        let email = creds.user_email.as_deref().unwrap_or("");
                        logger.info(LogCategory::Auth, "Trying silent re-auth with saved password");
                        match client.login(email, password).await {
                            Ok(result) => {
                                return AuthState::Authenticated { user: result.user };
                            }
                            Err(e3) => {
                                logger.error(LogCategory::Auth, format!("Silent re-auth failed: {e3}"));
                            }
                        }
                    }
                }
            }
        }
    }

    logger.info(LogCategory::Auth, "No valid credentials, needs login");
    AuthState::NeedsLogin
}

pub async fn login(
    client: &AgorClient,
    storage: &mut AppStorage,
    logger: &AppLogger,
    url: &str,
    email: &str,
    password: &str,
    profile_name: &str,
    save_password: bool,
) -> Result<AuthState, String> {
    logger.info(LogCategory::Auth, format!("Probing {url}..."));
    let base_url = client
        .probe_base_url(url)
        .await
        .map_err(|e| e.to_string())?;

    client.set_base_url(&base_url);

    logger.info(LogCategory::Auth, format!("Logging in as {email}..."));
    let result = client.login(email, password).await.map_err(|e| e.to_string())?;

    let profile_id = uuid::Uuid::new_v4().to_string();
    let profile = ServerProfile {
        id: profile_id.clone(),
        label: if profile_name.is_empty() {
            base_url.clone()
        } else {
            profile_name.to_string()
        },
        url: base_url,
        email: Some(email.to_string()),
        is_default: storage.profiles.is_empty(),
    };

    let existing = storage.profiles.iter().position(|p| p.url == profile.url && p.email.as_deref() == Some(email));

    let final_id = if let Some(idx) = existing {
        let id = storage.profiles[idx].id.clone();
        storage.profiles[idx].label = profile.label.clone();
        id
    } else {
        storage.add_profile(profile.clone());
        profile_id.clone()
    };

    let creds = ProfileCredentials {
        access_token: Some(result.access_token),
        refresh_token: result.refresh_token,
        user_id: Some(result.user.user_id.clone()),
        user_email: result.user.email.clone(),
        saved_password: if save_password {
            Some(password.to_string())
        } else {
            None
        },
        saved_api_key: None,
    };
    storage.save_profile_credentials(&final_id, creds);
    storage.set_active_profile(&final_id);

    logger.info(LogCategory::Auth, format!("Logged in as {}", result.user.name));

    Ok(AuthState::Authenticated {
        user: result.user,
    })
}

pub fn logout(client: &AgorClient, storage: &mut AppStorage, logger: &AppLogger) {
    logger.info(LogCategory::Auth, "Logging out");
    storage.clear_all_credentials();
    let mut tokens = client.tokens.write().unwrap();
    *tokens = crate::network::agor_client::AuthTokens::default();
}
