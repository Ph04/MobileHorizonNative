//! Verified downloads and a persistent cache for server resource packs.
//! Cache files are keyed by actual SHA-1. For protocol offers without a SHA-1,
//! a private URL-hash index allows reuse without storing signed URLs or tokens.

use sha1::{Digest, Sha1};
use sha2::Sha256;
use std::{
    collections::{BTreeMap, HashSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use url::Url;
use zip::ZipArchive;

const MAX_DOWNLOAD_BYTES: u64 = 250 * 1024 * 1024;
const MAX_EXPANDED_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_CACHE_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_ZIP_ENTRIES: usize = 100_000;
const MAX_META_BYTES: u64 = 64 * 1024;
const MAX_REDIRECTS: usize = 5;
const TOTAL_TIMEOUT: Duration = Duration::from_secs(120);

static NEXT_TEMP: AtomicU64 = AtomicU64::new(1);
static INDEX_LOCK: Mutex<()> = Mutex::new(());
static ACTIVE_PATHS: OnceLock<Mutex<HashSet<PathBuf>>> = OnceLock::new();

#[derive(Debug)]
pub enum FetchError {
    InvalidUrl(String),
    Failed(String),
}

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidUrl(reason) => write!(f, "{reason}"),
            Self::Failed(reason) => write!(f, "{reason}"),
        }
    }
}

impl std::error::Error for FetchError {}

/// Persistent cache below the app-private Horizon home.
pub fn cache_dir(base: &Path) -> PathBuf {
    base.join(".horizon-cache").join("server-resource-packs")
}

fn parse_pack_url(value: &str) -> Result<Url, FetchError> {
    if value.chars().any(char::is_control) {
        return Err(FetchError::InvalidUrl("resource-pack URL contains control characters".into()));
    }
    let url = Url::parse(value)
        .map_err(|_| FetchError::InvalidUrl("resource-pack URL is malformed".into()))?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err(FetchError::InvalidUrl(
            "only HTTP(S) resource-pack URLs without embedded credentials are supported".into(),
        ));
    }
    Ok(url)
}

pub fn is_supported_url(value: &str) -> bool {
    parse_https_url(value).is_ok()
}

fn valid_sha1(value: &str) -> bool {
    value.len() == 40 && value.bytes().all(|b| b.is_ascii_hexdigit())
}

fn sha1_file(path: &Path) -> Result<String, String> {
    let mut file = File::open(path).map_err(|e| format!("cannot open cached resource pack: {e}"))?;
    let mut hash = Sha1::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buffer).map_err(|e| format!("cannot read cached resource pack: {e}"))?;
        if n == 0 { break; }
        hash.update(&buffer[..n]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

fn unsafe_zip_name(name: &str) -> bool {
    name.starts_with('/')
        || name.starts_with('\\')
        || name.chars().any(char::is_control)
        || name.split(|c| c == '/' || c == '\\').any(|part| part == "..")
        || name.split(|c| c == '/' || c == '\\')
            .next()
            .is_some_and(|first| first.contains(':'))
}

fn validate_zip(path: &Path) -> Result<(), String> {
    let file = File::open(path).map_err(|e| format!("cannot open resource-pack ZIP: {e}"))?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("invalid resource-pack ZIP: {e}"))?;
    if archive.is_empty() || archive.len() > MAX_ZIP_ENTRIES {
        return Err("resource-pack ZIP has an invalid entry count".into());
    }

    let mut expanded = 0u64;
    for index in 0..archive.len() {
        let entry = archive.by_index(index).map_err(|e| format!("cannot inspect ZIP entry: {e}"))?;
        if entry.enclosed_name().is_none() || unsafe_zip_name(entry.name()) {
            return Err("resource-pack ZIP contains an unsafe path".into());
        }
        if !entry.is_dir() {
            expanded = expanded.saturating_add(entry.size());
            if entry.size() > MAX_DOWNLOAD_BYTES || expanded > MAX_EXPANDED_BYTES {
                return Err("resource-pack ZIP exceeds the expanded-size safety limit".into());
            }
        }
    }

    let mut meta = archive.by_name("pack.mcmeta")
        .map_err(|_| "resource-pack ZIP is missing root pack.mcmeta".to_string())?;
    if meta.size() == 0 || meta.size() > MAX_META_BYTES {
        return Err("resource-pack pack.mcmeta has an invalid size".into());
    }
    let mut bytes = Vec::new();
    (&mut meta).take(MAX_META_BYTES + 1).read_to_end(&mut bytes)
        .map_err(|e| format!("cannot read pack.mcmeta: {e}"))?;
    let json: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|e| format!("pack.mcmeta is not valid JSON: {e}"))?;
    if !json.get("pack").is_some_and(serde_json::Value::is_object) {
        return Err("pack.mcmeta has no pack object".into());
    }
    Ok(())
}

fn url_key(url: &str) -> String {
    format!("{:x}", Sha256::digest(url.as_bytes()))
}

fn read_url_index(dir: &Path) -> BTreeMap<String, String> {
    fs::read(dir.join("url-sha1-index.json")).ok()
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn indexed_sha1(dir: &Path, key: &str) -> Option<String> {
    let _guard = INDEX_LOCK.lock().ok()?;
    read_url_index(dir).get(key).cloned()
}

fn record_url_sha1(dir: &Path, key: &str, sha1: &str) {
    let Ok(_guard) = INDEX_LOCK.lock() else { return; };
    let mut index = read_url_index(dir);
    index.insert(key.to_owned(), sha1.to_owned());
    let Ok(bytes) = serde_json::to_vec(&index) else { return; };
    let nonce = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
    let temp = dir.join(format!(".url-index-{nonce}.tmp"));
    let target = dir.join("url-sha1-index.json");
    if fs::write(&temp, bytes).is_ok() {
        if fs::rename(&temp, &target).is_err() {
            let _ = fs::remove_file(&target);
            let _ = fs::rename(&temp, &target);
        }
    }
    let _ = fs::remove_file(temp);
}

fn active_paths() -> &'static Mutex<HashSet<PathBuf>> {
    ACTIVE_PATHS.get_or_init(|| Mutex::new(HashSet::new()))
}

/// Protect or unprotect a cache file currently used by a live resource overlay.
pub fn mark_active(path: &Path, active: bool) {
    let Ok(mut paths) = active_paths().lock() else { return; };
    if active { paths.insert(path.to_path_buf()); } else { paths.remove(path); }
}

fn prune_cache(dir: &Path, keep: &Path) {
    let active = active_paths().lock().map(|p| p.clone()).unwrap_or_default();
    let mut files = Vec::new();
    let mut total = 0u64;
    let Ok(read_dir) = fs::read_dir(dir) else { return; };
    for entry in read_dir.flatten() {
        let path = entry.path();
        if path.extension().and_then(|v| v.to_str()) != Some("zip") { continue; }
        let Ok(meta) = entry.metadata() else { continue; };
        if !meta.is_file() { continue; }
        let size = meta.len();
        total = total.saturating_add(size);
        files.push((meta.modified().unwrap_or(UNIX_EPOCH), path, size));
    }
    if total <= MAX_CACHE_BYTES { return; }
    files.sort_by_key(|(modified, _, _)| *modified);
    for (_, path, size) in files {
        if total <= MAX_CACHE_BYTES { break; }
        if path == keep || active.contains(&path) { continue; }
        if fs::remove_file(&path).is_ok() { total = total.saturating_sub(size); }
    }
}

fn touch(path: &Path) {
    if let Ok(file) = OpenOptions::new().write(true).open(path) {
        let _ = file.set_modified(SystemTime::now());
    }
}

fn open_pack_url(agent: &ureq::Agent, initial: Url) -> Result<ureq::Response, FetchError> {
    let mut current = initial;
    for redirect_number in 0..=MAX_REDIRECTS {
        let response = match agent.get(current.as_str()).call() {
            Ok(response) => response,
            Err(ureq::Error::Status(code, response)) if (300..400).contains(&code) => response,
            Err(ureq::Error::Status(code, _)) => {
                return Err(FetchError::Failed(format!("resource-pack server returned HTTP {code}")));
            }
            Err(error) => {
                return Err(FetchError::Failed(format!("resource-pack HTTP request failed: {error}")));
            }
        };

        let status = response.status();
        if (300..400).contains(&status) {
            if redirect_number == MAX_REDIRECTS {
                return Err(FetchError::Failed("resource-pack URL exceeded the redirect limit".into()));
            }
            let location = response.header("Location")
                .ok_or_else(|| FetchError::Failed("resource-pack redirect omitted Location".into()))?;
            let next = current.join(location)
                .map_err(|_| FetchError::InvalidUrl("resource-pack redirect URL is malformed".into()))?;
            // Match Java's HTTP(S) support but never allow an HTTPS downgrade.
            let scheme_allowed = next.scheme() == "https"
                || (current.scheme() == "http" && next.scheme() == "http");
            if !scheme_allowed || next.host_str().is_none()
                || !next.username().is_empty() || next.password().is_some()
            {
                return Err(FetchError::InvalidUrl(
                    "resource-pack redirects must use HTTP(S) without credentials and cannot downgrade HTTPS".into()
                ));
            }
            current = next;
            continue;
        }
        if !(200..300).contains(&status) {
            return Err(FetchError::Failed(format!("resource-pack server returned HTTP {status}")));
        }
        return Ok(response);
    }
    Err(FetchError::Failed("resource-pack redirect limit reached".into()))
}

/// Download a pack into the private cache, using the expected SHA-1 if supplied.
/// The Java protocol allows an empty SHA-1 field; when it is absent the URL-hash
/// index reuses verified content without persisting the raw URL or its tokens.
/// Progress receives downloaded bytes and optional Content-Length.
pub fn fetch(
    base: &Path,
    id: u128,
    url: &str,
    expected_sha1: &str,
    mut progress: impl FnMut(u64, Option<u64>),
) -> Result<PathBuf, FetchError> {
    let parsed_url = parse_pack_url(url)?;
    let expected = expected_sha1.trim().to_ascii_lowercase();
    if !expected.is_empty() && !valid_sha1(&expected) {
        return Err(FetchError::Failed("server supplied a malformed SHA-1".into()));
    }

    let dir = cache_dir(base);
    fs::create_dir_all(&dir)
        .map_err(|e| FetchError::Failed(format!("cannot create private resource-pack cache: {e}")))?;
    let key = url_key(url);
    let known_hash = if expected.is_empty() {
        indexed_sha1(&dir, &key).filter(|hash| valid_sha1(hash))
    } else {
        Some(expected.clone())
    };

    if let Some(hash) = known_hash {
        let cached = dir.join(format!("{hash}.zip"));
        if cached.is_file()
            && sha1_file(&cached).is_ok_and(|actual| actual.eq_ignore_ascii_case(&hash))
            && validate_zip(&cached).is_ok()
        {
            log::info!("Horizon: server resource-pack cache hit id={id:032x} sha1={hash}");
            touch(&cached);
            return Ok(cached);
        }
        if cached.exists() {
            log::warn!("Horizon: server resource-pack cache entry failed verification id={id:032x} sha1={hash}; redownloading");
            let _ = fs::remove_file(&cached);
        }
    }

    let nonce = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
    let temp = dir.join(format!(".{id:032x}-{stamp}-{nonce}.part"));
    let mut file = OpenOptions::new().write(true).create_new(true).open(&temp)
        .map_err(|e| FetchError::Failed(format!("cannot create temporary resource-pack file: {e}")))?;

    let agent = ureq::AgentBuilder::new()
        .redirects(0)
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(30))
        .timeout_write(Duration::from_secs(30))
        .build();
    let response = match open_pack_url(&agent, parsed_url) {
        Ok(response) => response,
        Err(error) => {
            drop(file);
            let _ = fs::remove_file(&temp);
            return Err(error);
        }
    };
    let content_length = response.header("Content-Length").and_then(|v| v.parse::<u64>().ok());
    if content_length.is_some_and(|size| size > MAX_DOWNLOAD_BYTES) {
        drop(file);
        let _ = fs::remove_file(&temp);
        return Err(FetchError::Failed("resource pack exceeds the 250 MiB download limit".into()));
    }

    let began = Instant::now();
    let mut reader = response.into_reader();
    let mut hash = Sha1::new();
    let mut downloaded = 0u64;
    let mut buffer = [0u8; 64 * 1024];
    progress(0, content_length);
    let transfer_result = (|| -> Result<(), FetchError> {
        loop {
            let count = reader.read(&mut buffer)
                .map_err(|e| FetchError::Failed(format!("resource-pack read failed: {e}")))?;
            if count == 0 { break; }
            if began.elapsed() > TOTAL_TIMEOUT {
                return Err(FetchError::Failed("resource-pack download exceeded the total timeout".into()));
            }
            downloaded = downloaded.saturating_add(count as u64);
            if downloaded > MAX_DOWNLOAD_BYTES {
                return Err(FetchError::Failed("resource pack exceeds the 250 MiB download limit".into()));
            }
            hash.update(&buffer[..count]);
            file.write_all(&buffer[..count])
                .map_err(|e| FetchError::Failed(format!("cannot write resource-pack cache: {e}")))?;
            progress(downloaded, content_length);
        }
        if downloaded == 0 {
            return Err(FetchError::Failed("resource-pack server returned an empty file".into()));
        }
        file.sync_all().map_err(|e| FetchError::Failed(format!("cannot sync resource-pack cache: {e}")))?;
        Ok(())
    })();
    drop(file);
    if let Err(error) = transfer_result {
        let _ = fs::remove_file(&temp);
        return Err(error);
    }

    let actual_sha1 = format!("{:x}", hash.finalize());
    if !expected.is_empty() && !actual_sha1.eq_ignore_ascii_case(&expected) {
        let _ = fs::remove_file(&temp);
        return Err(FetchError::Failed("resource-pack SHA-1 did not match the server offer".into()));
    }
    if let Err(reason) = validate_zip(&temp) {
        let _ = fs::remove_file(&temp);
        return Err(FetchError::Failed(reason));
    }

    log::info!("Horizon: server resource-pack validated id={id:032x} sha1={actual_sha1} bytes={downloaded}");
    let target = dir.join(format!("{actual_sha1}.zip"));
    if target.is_file() && sha1_file(&target).is_ok_and(|existing| existing == actual_sha1) {
        let _ = fs::remove_file(&temp);
    } else {
        if target.exists() { let _ = fs::remove_file(&target); }
        fs::rename(&temp, &target)
            .map_err(|e| FetchError::Failed(format!("cannot promote verified resource pack into cache: {e}")))?;
    }
    record_url_sha1(&dir, &key, &actual_sha1);
    touch(&target);
    prune_cache(&dir, &target);
    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_java_http_and_https_without_credentials() {
        assert!(is_supported_url("https://example.com/pack.zip"));
        assert!(is_supported_url("http://example.com/pack.zip"));
        assert!(!is_supported_url("ftp://example.com/pack.zip"));
        assert!(!is_supported_url("file:///tmp/pack.zip"));
        assert!(!is_supported_url("https://user:secret@example.com/pack.zip"));
        assert!(!is_supported_url("http://user:secret@example.com/pack.zip"));
    }

    #[test]
    fn sha1_requires_forty_hex_characters_when_present() {
        assert!(valid_sha1("0123456789abcdef0123456789abcdef01234567"));
        assert!(!valid_sha1(""));
        assert!(!valid_sha1("012345"));
        assert!(!valid_sha1("g123456789abcdef0123456789abcdef01234567"));
    }

    #[test]
    fn rejects_traversal_zip_names() {
        assert!(unsafe_zip_name("../outside.png"));
        assert!(unsafe_zip_name("assets/../../outside.png"));
        assert!(unsafe_zip_name("C:\\outside.png"));
        assert!(!unsafe_zip_name("assets/minecraft/textures/block/stone.png"));
    }
}
