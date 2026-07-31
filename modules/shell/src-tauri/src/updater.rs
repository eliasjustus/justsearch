use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use base64::Engine;
use ring::signature::{UnparsedPublicKey, ED25519};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Manager};
use tauri_plugin_updater::UpdaterExt;

use crate::BackendState;

const INTENT_SCHEMA_VERSION: u32 = 1;
const DESCRIPTOR_SCHEMA_VERSION: u32 = 1;
const HEAD_TIMEOUT: Duration = Duration::from_secs(15);
const BACKEND_EXIT_TIMEOUT: Duration = Duration::from_secs(30);
const LOCAL_STORE_REGISTER: &str =
    include_str!("../../../../governance/store-recoverability.v1.json");

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseDescriptor {
    schema_version: u32,
    sequence: u64,
    version: String,
    channel: String,
    target: String,
    artifact: ReleaseArtifact,
    compatibility: Vec<ReleaseStoreCompatibility>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseArtifact {
    url: String,
    sha256: String,
    signature: String,
    public_key: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseStoreCompatibility {
    owner_id: String,
    current_version: u32,
    readable_source_versions: Vec<u32>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalStoreRegister {
    durable_stores: Vec<LocalDurableStore>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalDurableStore {
    id: String,
    current_version: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum UpgradePhase {
    Prepared,
    InstallLaunched,
    Reconciling,
    Committed,
    RepairRequired,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpgradeIntent {
    schema_version: u32,
    phase: UpgradePhase,
    preparation_id: String,
    source_version: String,
    target_version: String,
    release_sequence: u64,
    updated_at_epoch_ms: u128,
    error: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SequenceState {
    schema_version: u32,
    highest_accepted_sequence: u64,
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdateStatus {
    state: String,
    current_version: String,
    available_version: Option<String>,
    release_sequence: Option<u64>,
    intent_phase: Option<UpgradePhase>,
    error: Option<String>,
}

#[derive(Default)]
pub struct UpdateCoordinator {
    pending: Mutex<Option<ReleaseDescriptor>>,
    status: Mutex<AppUpdateStatus>,
    installing: AtomicBool,
}

impl UpdateCoordinator {
    pub fn initialize(&self, app: &AppHandle) {
        let current = app.package_info().version.to_string();
        let mut status = self.status.lock().expect("update status mutex poisoned");
        status.current_version = current.clone();
        status.state = "idle".into();

        let path = intent_path(app);
        match read_intent(&path).and_then(|intent| {
            intent
                .map(|value| reconcile_intent(&path, &current, value))
                .transpose()
                .map(Option::flatten)
        }) {
            Ok(Some(intent)) => {
                status.state = phase_state(&intent.phase).into();
                status.available_version = Some(intent.target_version.clone());
                status.release_sequence = Some(intent.release_sequence);
                status.error = intent.error.clone();
                status.intent_phase = Some(intent.phase);
            }
            Ok(None) => {}
            Err(error) => {
                status.state = "repair_required".into();
                status.error = Some(error);
            }
        }
    }

    fn snapshot(&self) -> AppUpdateStatus {
        self.status
            .lock()
            .expect("update status mutex poisoned")
            .clone()
    }
}

#[tauri::command]
pub fn app_update_status(coordinator: tauri::State<'_, Arc<UpdateCoordinator>>) -> AppUpdateStatus {
    coordinator.snapshot()
}

#[tauri::command]
pub async fn check_for_app_update(
    app: AppHandle,
    coordinator: tauri::State<'_, Arc<UpdateCoordinator>>,
) -> Result<AppUpdateStatus, String> {
    ensure_no_unresolved_intent(&coordinator)?;
    if coordinator.installing.load(Ordering::Acquire) {
        return Err("An update installation is already in progress".into());
    }
    set_checking(&coordinator);
    let result = check_release(&app).await;
    match result {
        Ok(Some(descriptor)) => {
            let mut pending = coordinator
                .pending
                .lock()
                .expect("pending update mutex poisoned");
            *pending = Some(descriptor.clone());
            let mut status = coordinator
                .status
                .lock()
                .expect("update status mutex poisoned");
            status.state = "available".into();
            status.available_version = Some(descriptor.version.clone());
            status.release_sequence = Some(descriptor.sequence);
            status.error = None;
            Ok(status.clone())
        }
        Ok(None) => {
            let mut status = coordinator
                .status
                .lock()
                .expect("update status mutex poisoned");
            status.state = "up_to_date".into();
            status.available_version = None;
            status.release_sequence = None;
            status.error = None;
            Ok(status.clone())
        }
        Err(error) => {
            let mut status = coordinator
                .status
                .lock()
                .expect("update status mutex poisoned");
            status.state = "error".into();
            status.error = Some(error.clone());
            Err(error)
        }
    }
}

#[tauri::command]
pub async fn install_app_update(
    app: AppHandle,
    backend: tauri::State<'_, Arc<BackendState>>,
    coordinator: tauri::State<'_, Arc<UpdateCoordinator>>,
) -> Result<(), String> {
    ensure_no_unresolved_intent(&coordinator)?;
    let _install_guard = InstallGuard::begin(&coordinator.installing)?;
    let descriptor = coordinator
        .pending
        .lock()
        .expect("pending update mutex poisoned")
        .clone()
        .ok_or_else(|| "No authenticated update is pending; check for updates first".to_string())?;

    let latest_url = latest_url()?;
    let updater = app
        .updater_builder()
        .pubkey(descriptor.artifact.public_key.clone())
        .endpoints(vec![latest_url
            .parse()
            .map_err(|error| format!("Invalid updater endpoint: {error}"))?])
        .map_err(|error| format!("Updater endpoint rejected: {error}"))?
        .build()
        .map_err(|error| format!("Updater initialization failed: {error}"))?;
    let update = updater
        .check()
        .await
        .map_err(|error| format!("Updater check failed: {error}"))?
        .ok_or_else(|| "Authenticated release is no longer available".to_string())?;
    require_closed_set(&descriptor, &update)?;

    set_state(&coordinator, "downloading", None);
    let bytes = update
        .download(|_, _| {}, || {})
        .await
        .map_err(|error| format!("Update download or signature verification failed: {error}"))?;
    let digest = format!("{:x}", Sha256::digest(&bytes));
    if digest != descriptor.artifact.sha256.to_ascii_lowercase() {
        return Err("Downloaded installer digest does not match release descriptor".into());
    }

    let prepared = prepare_head(&backend).await?;
    if !prepared.ready {
        let _ = cancel_head(&backend, &prepared.preparation_id).await;
        return Err("Update is blocked by a non-interruptible operation".into());
    }
    let intent = UpgradeIntent {
        schema_version: INTENT_SCHEMA_VERSION,
        phase: UpgradePhase::Prepared,
        preparation_id: prepared.preparation_id.clone(),
        source_version: app.package_info().version.to_string(),
        target_version: descriptor.version.clone(),
        release_sequence: descriptor.sequence,
        updated_at_epoch_ms: now_epoch_ms(),
        error: None,
    };
    if let Err(error) = write_json_atomic(&intent_path(&app), &intent) {
        let _ = cancel_head(&backend, &prepared.preparation_id).await;
        return Err(error);
    }
    set_intent_status(&coordinator, &intent);

    if let Err(error) = commit_head(&backend, &prepared.preparation_id).await {
        let _ = cancel_head(&backend, &prepared.preparation_id).await;
        return Err(error);
    }
    if !backend.wait_for_child_exit(BACKEND_EXIT_TIMEOUT) {
        let _ = mark_repair_required(&app, &coordinator, intent, "Backend did not exit in time");
        return Err("Backend did not complete orderly shutdown; installer was not launched".into());
    }

    let mut launched = intent;
    launched.phase = UpgradePhase::InstallLaunched;
    launched.updated_at_epoch_ms = now_epoch_ms();
    write_json_atomic(&intent_path(&app), &launched)?;
    set_intent_status(&coordinator, &launched);
    update.install(bytes).map_err(|error| {
        let message = format!("Installer launch failed after backend shutdown: {error}");
        let _ = mark_repair_required(&app, &coordinator, launched, &message);
        message
    })
}

async fn check_release(app: &AppHandle) -> Result<Option<ReleaseDescriptor>, String> {
    let descriptor_url = descriptor_url()?;
    ensure_https(&descriptor_url)?;
    let signature_url = format!("{descriptor_url}.sig");
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|error| format!("Failed to create release client: {error}"))?;
    let descriptor_bytes = fetch_bytes(&client, &descriptor_url).await?;
    let signature = fetch_text(&client, &signature_url).await?;
    verify_metadata_signature(&descriptor_bytes, signature.trim())?;
    let descriptor: ReleaseDescriptor = serde_json::from_slice(&descriptor_bytes)
        .map_err(|error| format!("Release descriptor is invalid: {error}"))?;
    validate_descriptor(&descriptor)?;

    let sequence_path = sequence_path(app);
    let sequence = read_sequence(&sequence_path)?;
    if descriptor.sequence < sequence.highest_accepted_sequence {
        return Err(format!(
            "Release sequence {} is older than the accepted sequence {}",
            descriptor.sequence, sequence.highest_accepted_sequence
        ));
    }

    let latest = latest_url()?;
    let updater = app
        .updater_builder()
        .pubkey(descriptor.artifact.public_key.clone())
        .endpoints(vec![latest
            .parse()
            .map_err(|error| format!("Invalid updater endpoint: {error}"))?])
        .map_err(|error| format!("Updater endpoint rejected: {error}"))?
        .build()
        .map_err(|error| format!("Updater initialization failed: {error}"))?;
    let update = updater
        .check()
        .await
        .map_err(|error| format!("Updater check failed: {error}"))?;
    let Some(update) = update else {
        return Ok(None);
    };
    require_closed_set(&descriptor, &update)?;
    write_json_atomic(
        &sequence_path,
        &SequenceState {
            schema_version: 1,
            highest_accepted_sequence: descriptor.sequence,
        },
    )?;
    Ok(Some(descriptor))
}

fn require_closed_set(
    descriptor: &ReleaseDescriptor,
    update: &tauri_plugin_updater::Update,
) -> Result<(), String> {
    if update.version != descriptor.version
        || update.download_url.as_str() != descriptor.artifact.url
        || update.signature.trim() != descriptor.artifact.signature.trim()
    {
        return Err("release.v1.json and latest.json do not describe the same artifact".into());
    }
    Ok(())
}

fn validate_descriptor(descriptor: &ReleaseDescriptor) -> Result<(), String> {
    if descriptor.schema_version != DESCRIPTOR_SCHEMA_VERSION
        || descriptor.channel != "stable"
        || descriptor.target != "windows-x86_64"
        || descriptor.sequence == 0
        || descriptor.version.trim().is_empty()
    {
        return Err(
            "Release descriptor schema, channel, target, sequence, or version is invalid".into(),
        );
    }
    ensure_https(&descriptor.artifact.url)?;
    if descriptor.artifact.sha256.len() != 64
        || descriptor.artifact.signature.trim().is_empty()
        || descriptor.artifact.public_key.trim().is_empty()
    {
        return Err("Release descriptor artifact fields are incomplete".into());
    }
    validate_store_compatibility(descriptor)?;
    Ok(())
}

fn validate_store_compatibility(descriptor: &ReleaseDescriptor) -> Result<(), String> {
    let local: LocalStoreRegister = serde_json::from_str(LOCAL_STORE_REGISTER)
        .map_err(|error| format!("Embedded store compatibility register is invalid: {error}"))?;
    let mut release_owners = std::collections::HashMap::new();
    for compatibility in &descriptor.compatibility {
        if compatibility.owner_id.trim().is_empty()
            || release_owners
                .insert(compatibility.owner_id.as_str(), compatibility)
                .is_some()
        {
            return Err(format!(
                "Release compatibility owner is missing or duplicated: {}",
                compatibility.owner_id
            ));
        }
        if !compatibility
            .readable_source_versions
            .contains(&compatibility.current_version)
        {
            return Err(format!(
                "Release compatibility owner {} cannot read its own current version {}",
                compatibility.owner_id, compatibility.current_version
            ));
        }
    }
    for store in local.durable_stores {
        let compatibility = release_owners.get(store.id.as_str()).ok_or_else(|| {
            format!(
                "Release does not declare compatibility for durable store {}",
                store.id
            )
        })?;
        if !compatibility
            .readable_source_versions
            .contains(&store.current_version)
        {
            return Err(format!(
                "Release cannot read durable store {} version {}",
                store.id, store.current_version
            ));
        }
    }
    Ok(())
}

fn verify_metadata_signature(bytes: &[u8], signature: &str) -> Result<(), String> {
    let key = base64::engine::general_purpose::STANDARD
        .decode(metadata_root_key()?.trim())
        .map_err(|error| format!("Metadata root key is not base64: {error}"))?;
    if key.len() != 32 {
        return Err("Metadata root key must decode to 32 Ed25519 bytes".into());
    }
    let signature = base64::engine::general_purpose::STANDARD
        .decode(signature)
        .map_err(|error| format!("Metadata signature is not base64: {error}"))?;
    UnparsedPublicKey::new(&ED25519, key)
        .verify(bytes, &signature)
        .map_err(|_| "Release metadata signature verification failed".into())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrepareResponse {
    preparation_id: String,
    ready: bool,
}

async fn prepare_head(backend: &BackendState) -> Result<PrepareResponse, String> {
    post_head(backend, "/api/upgrade/prepare", None).await
}

async fn cancel_head(
    backend: &BackendState,
    preparation_id: &str,
) -> Result<serde_json::Value, String> {
    post_head(
        backend,
        "/api/upgrade/cancel",
        Some(serde_json::json!({ "preparationId": preparation_id })),
    )
    .await
}

async fn commit_head(
    backend: &BackendState,
    preparation_id: &str,
) -> Result<serde_json::Value, String> {
    post_head(
        backend,
        "/api/upgrade/commit-shutdown",
        Some(serde_json::json!({ "preparationId": preparation_id })),
    )
    .await
}

async fn post_head<T: for<'de> Deserialize<'de>>(
    backend: &BackendState,
    path: &str,
    body: Option<serde_json::Value>,
) -> Result<T, String> {
    let port = backend
        .get_port()
        .ok_or_else(|| "Backend is unavailable".to_string())?;
    let token = backend
        .get_session_token()
        .ok_or_else(|| "Backend session token is unavailable".to_string())?;
    let client = reqwest::Client::builder()
        .timeout(HEAD_TIMEOUT)
        .build()
        .map_err(|error| format!("Failed to create backend client: {error}"))?;
    let mut request = client
        .post(format!("http://127.0.0.1:{port}{path}"))
        .header("X-JustSearch-Session", token);
    if let Some(body) = body {
        request = request.json(&body);
    }
    let response = request
        .send()
        .await
        .map_err(|error| format!("Backend upgrade handshake failed: {error}"))?;
    let status = response.status();
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("Backend upgrade response failed: {error}"))?;
    if !status.is_success() {
        return Err(format!(
            "Backend rejected upgrade handshake with {status}: {}",
            String::from_utf8_lossy(&bytes)
        ));
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("Backend upgrade response was invalid: {error}"))
}

async fn fetch_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>, String> {
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("Release request failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Release request failed: {error}"))?;
    response
        .bytes()
        .await
        .map(|bytes| bytes.to_vec())
        .map_err(|error| format!("Release body failed: {error}"))
}

async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String, String> {
    let bytes = fetch_bytes(client, url).await?;
    String::from_utf8(bytes).map_err(|error| format!("Release signature is not UTF-8: {error}"))
}

fn descriptor_url() -> Result<String, String> {
    pinned_release_value(
        "JUSTSEARCH_RELEASE_DESCRIPTOR_URL",
        option_env!("JUSTSEARCH_RELEASE_DESCRIPTOR_URL"),
    )
}

fn latest_url() -> Result<String, String> {
    let descriptor = descriptor_url()?;
    descriptor
        .strip_suffix("release.v1.json")
        .map(|prefix| format!("{prefix}latest.json"))
        .ok_or_else(|| "Release descriptor URL must end with release.v1.json".into())
}

fn metadata_root_key() -> Result<String, String> {
    pinned_release_value(
        "JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY",
        option_env!("JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY"),
    )
}

fn pinned_release_value(name: &str, built: Option<&'static str>) -> Result<String, String> {
    if let Some(value) = built.filter(|value| !value.trim().is_empty()) {
        return Ok(value.to_owned());
    }
    #[cfg(debug_assertions)]
    {
        return std::env::var(name)
            .ok()
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| format!("{name} is not configured"));
    }
    #[cfg(not(debug_assertions))]
    Err(format!("{name} was not pinned into this release build"))
}

fn ensure_https(url: &str) -> Result<(), String> {
    if url.starts_with("https://") {
        Ok(())
    } else {
        Err("Release endpoints must use HTTPS".into())
    }
}

fn intent_path(app: &AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("app data directory unavailable")
        .join("upgrade")
        .join("intent.v1.json")
}

fn sequence_path(app: &AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("app data directory unavailable")
        .join("upgrade")
        .join("sequence.v1.json")
}

fn read_intent(path: &Path) -> Result<Option<UpgradeIntent>, String> {
    if !path.exists() {
        return Ok(None);
    }
    let intent: UpgradeIntent = read_json(path)?;
    if intent.schema_version != INTENT_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported upgrade intent schema version {}",
            intent.schema_version
        ));
    }
    Ok(Some(intent))
}

fn reconcile_intent(
    path: &Path,
    current_version: &str,
    mut intent: UpgradeIntent,
) -> Result<Option<UpgradeIntent>, String> {
    match intent.phase {
        UpgradePhase::Prepared | UpgradePhase::InstallLaunched
            if intent.source_version == current_version =>
        {
            fs::remove_file(path)
                .map_err(|error| format!("Failed to cancel stale upgrade handoff: {error}"))?;
            return Ok(None);
        }
        UpgradePhase::Prepared | UpgradePhase::InstallLaunched | UpgradePhase::Reconciling => {
            intent.updated_at_epoch_ms = now_epoch_ms();
            if intent.target_version == current_version {
                intent.phase = UpgradePhase::Reconciling;
                intent.error =
                    Some("Installed version is awaiting durable-owner reconciliation".into());
            } else {
                intent.phase = UpgradePhase::RepairRequired;
                intent.error = Some(format!(
                    "Expected version {} after installer handoff, running {}",
                    intent.target_version, current_version
                ));
            }
            write_json_atomic(path, &intent)?;
        }
        UpgradePhase::Committed | UpgradePhase::RepairRequired => {}
    }
    Ok(Some(intent))
}

fn read_sequence(path: &Path) -> Result<SequenceState, String> {
    if !path.exists() {
        return Ok(SequenceState {
            schema_version: 1,
            highest_accepted_sequence: 0,
        });
    }
    let state: SequenceState = read_json(path)?;
    if state.schema_version != 1 {
        return Err(format!(
            "Unsupported upgrade sequence schema version {}",
            state.schema_version
        ));
    }
    Ok(state)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T, String> {
    let bytes =
        fs::read(path).map_err(|error| format!("Failed to read {}: {error}", path.display()))?;
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("Failed to parse {}: {error}", path.display()))
}

fn write_json_atomic(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| format!("Path has no parent: {}", path.display()))?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Failed to create {}: {error}", parent.display()))?;
    let temp = path.with_extension("json.tmp");
    let bytes = serde_json::to_vec_pretty(value)
        .map_err(|error| format!("Failed to serialize {}: {error}", path.display()))?;
    fs::write(&temp, bytes)
        .map_err(|error| format!("Failed to write {}: {error}", temp.display()))?;
    replace_file(&temp, path).map_err(|error| {
        let _ = fs::remove_file(&temp);
        format!("Failed to replace {}: {error}", path.display())
    })
}

#[cfg(not(windows))]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    fs::rename(source, target)
}

#[cfg(windows)]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let source_wide: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let target_wide: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let moved = unsafe {
        MoveFileExW(
            source_wide.as_ptr(),
            target_wide.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if moved == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn set_checking(coordinator: &UpdateCoordinator) {
    set_state(coordinator, "checking", None);
}

fn set_state(coordinator: &UpdateCoordinator, state: &str, error: Option<String>) {
    let mut status = coordinator
        .status
        .lock()
        .expect("update status mutex poisoned");
    status.state = state.into();
    status.error = error;
}

fn set_intent_status(coordinator: &UpdateCoordinator, intent: &UpgradeIntent) {
    let mut status = coordinator
        .status
        .lock()
        .expect("update status mutex poisoned");
    status.state = phase_state(&intent.phase).into();
    status.intent_phase = Some(intent.phase.clone());
    status.error = intent.error.clone();
}

fn ensure_no_unresolved_intent(coordinator: &UpdateCoordinator) -> Result<(), String> {
    let status = coordinator.snapshot();
    match status.intent_phase {
        Some(UpgradePhase::Prepared)
        | Some(UpgradePhase::InstallLaunched)
        | Some(UpgradePhase::Reconciling)
        | Some(UpgradePhase::RepairRequired) => Err(format!(
            "The previous update is unresolved (state {}); repair or reconcile it before continuing",
            status.state
        )),
        Some(UpgradePhase::Committed) | None => Ok(()),
    }
}

fn mark_repair_required(
    app: &AppHandle,
    coordinator: &UpdateCoordinator,
    mut intent: UpgradeIntent,
    error: &str,
) -> Result<(), String> {
    intent.phase = UpgradePhase::RepairRequired;
    intent.updated_at_epoch_ms = now_epoch_ms();
    intent.error = Some(error.into());
    write_json_atomic(&intent_path(app), &intent)?;
    set_intent_status(coordinator, &intent);
    Ok(())
}

struct InstallGuard<'a>(&'a AtomicBool);

impl<'a> InstallGuard<'a> {
    fn begin(flag: &'a AtomicBool) -> Result<Self, String> {
        flag.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| "An update installation is already in progress".to_string())?;
        Ok(Self(flag))
    }
}

impl Drop for InstallGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

fn phase_state(phase: &UpgradePhase) -> &'static str {
    match phase {
        UpgradePhase::Prepared => "prepared",
        UpgradePhase::InstallLaunched => "install_launched",
        UpgradePhase::Reconciling => "reconciling",
        UpgradePhase::Committed => "committed",
        UpgradePhase::RepairRequired => "repair_required",
    }
}

fn now_epoch_ms() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn sequence_state_refuses_unknown_schema() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("sequence.v1.json");
        fs::write(&path, r#"{"schemaVersion":2,"highestAcceptedSequence":7}"#).unwrap();
        assert!(read_sequence(&path)
            .unwrap_err()
            .contains("Unsupported upgrade sequence"));
    }

    #[test]
    fn intent_round_trip_preserves_durable_phase() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("intent.v1.json");
        let intent = UpgradeIntent {
            schema_version: 1,
            phase: UpgradePhase::InstallLaunched,
            preparation_id: "prep-1".into(),
            source_version: "1.0.0".into(),
            target_version: "1.1.0".into(),
            release_sequence: 3,
            updated_at_epoch_ms: 10,
            error: None,
        };
        write_json_atomic(&path, &intent).unwrap();
        let restored = read_intent(&path).unwrap().unwrap();
        assert_eq!(restored.phase, UpgradePhase::InstallLaunched);
        assert_eq!(restored.release_sequence, 3);
    }

    #[test]
    fn prepared_intent_on_source_version_is_cancelled() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("intent.v1.json");
        let intent = test_intent(UpgradePhase::Prepared);
        write_json_atomic(&path, &intent).unwrap();

        assert!(reconcile_intent(&path, "1.0.0", intent).unwrap().is_none());
        assert!(!path.exists());
    }

    #[test]
    fn launched_intent_on_source_version_is_treated_as_cancelled_handoff() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("intent.v1.json");
        let intent = test_intent(UpgradePhase::InstallLaunched);
        write_json_atomic(&path, &intent).unwrap();

        assert!(reconcile_intent(&path, "1.0.0", intent).unwrap().is_none());
        assert!(!path.exists());
    }

    #[test]
    fn installed_target_remains_reconciling_until_owners_report_healthy() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("intent.v1.json");
        let intent = test_intent(UpgradePhase::InstallLaunched);
        write_json_atomic(&path, &intent).unwrap();

        let reconciled = reconcile_intent(&path, "1.1.0", intent).unwrap().unwrap();
        assert_eq!(reconciled.phase, UpgradePhase::Reconciling);
        assert!(reconciled.error.unwrap().contains("durable-owner"));
    }

    #[test]
    fn unresolved_reconciliation_blocks_another_update_cycle() {
        let coordinator = UpdateCoordinator::default();
        {
            let mut status = coordinator.status.lock().unwrap();
            status.state = "reconciling".into();
            status.intent_phase = Some(UpgradePhase::Reconciling);
        }

        assert!(ensure_no_unresolved_intent(&coordinator)
            .unwrap_err()
            .contains("previous update is unresolved"));
    }

    #[test]
    fn compatibility_rejects_release_that_cannot_read_local_store() {
        let local: LocalStoreRegister = serde_json::from_str(LOCAL_STORE_REGISTER).unwrap();
        let compatibility = local
            .durable_stores
            .iter()
            .map(|store| ReleaseStoreCompatibility {
                owner_id: store.id.clone(),
                current_version: store.current_version,
                readable_source_versions: vec![store.current_version],
            })
            .collect();
        let mut descriptor = test_descriptor(compatibility);
        descriptor.compatibility[0].readable_source_versions.clear();

        assert!(validate_store_compatibility(&descriptor)
            .unwrap_err()
            .contains("cannot read its own current version"));
    }

    fn test_intent(phase: UpgradePhase) -> UpgradeIntent {
        UpgradeIntent {
            schema_version: 1,
            phase,
            preparation_id: "prep-1".into(),
            source_version: "1.0.0".into(),
            target_version: "1.1.0".into(),
            release_sequence: 3,
            updated_at_epoch_ms: 10,
            error: None,
        }
    }

    fn test_descriptor(compatibility: Vec<ReleaseStoreCompatibility>) -> ReleaseDescriptor {
        ReleaseDescriptor {
            schema_version: 1,
            sequence: 3,
            version: "1.1.0".into(),
            channel: "stable".into(),
            target: "windows-x86_64".into(),
            artifact: ReleaseArtifact {
                url: "https://updates.example/installer.exe".into(),
                sha256: "0".repeat(64),
                signature: "signature".into(),
                public_key: "public-key".into(),
            },
            compatibility,
        }
    }
}
