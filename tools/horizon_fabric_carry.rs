//! Direct-JAR content carry-over for MobileHorizonNative.
//! Java classes and Mixins are never loaded or executed.
use horizon_mods::{Content, Package};
use horizon_port_pack::{carry, jar};
use serde_json::json;
use std::{
    collections::BTreeSet,
    env,
    fs::{self, File},
    io,
    path::{Path, PathBuf},
    process::ExitCode,
};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("FABRIC_CARRY_ERROR:{error}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<(), String> {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() < 2 || args.len() > 3 {
        return Err("usage: horizon-fabric-carry <input.jar> <output.hmod> [prepared-resources-dir]".into());
    }
    let jar_path = PathBuf::from(&args[0]);
    let output_path = PathBuf::from(&args[1]);
    if !jar_path.is_file() {
        return Err(format!("input JAR does not exist: {}", jar_path.display()));
    }
    let info = jar::read(&jar_path)?;
    let id = info.mod_id.clone();
    if id.is_empty()
        || id.len() > 64
        || !id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
    {
        return Err(format!("mod ID {id:?} is not a valid Horizon content ID ([a-z0-9_] up to 64 chars)"));
    }

    let mut vanilla_blocks: Option<BTreeSet<String>> = None;
    if let Some(resource_root) = args.get(2).map(PathBuf::from) {
        let facts_path = resource_root.join("facts.json");
        if facts_path.is_file() {
            let raw = fs::read_to_string(&facts_path)
                .map_err(|e| format!("{}: {e}", facts_path.display()))?;
            let document: serde_json::Value = serde_json::from_str(&raw)
                .map_err(|e| format!("{}: {e}", facts_path.display()))?;
            if let Some(defaults) = document.get("defaults").and_then(serde_json::Value::as_object) {
                vanilla_blocks = Some(defaults.keys().cloned().collect());
            }
        }
    }
    let allowed = |block: &str| vanilla_blocks.as_ref().is_none_or(|blocks| blocks.contains(block));
    let loaded = |other: &str| other == "minecraft" || other == id.as_str();
    let in_pack = |other: &str| other == "minecraft" || other == id.as_str();
    let pack = carry::Pack { loaded: &loaded, mods: &in_pack };
    let conventions = std::collections::BTreeMap::new();

    let parent = output_path.parent().ok_or("output path must have a parent directory")?;
    fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    let work = parent.join(format!(".{id}.fabric-carry-work"));
    if work.exists() {
        fs::remove_dir_all(&work).map_err(|e| format!("{}: {e}", work.display()))?;
    }
    fs::create_dir_all(&work).map_err(|e| format!("{}: {e}", work.display()))?;

    let carried = carry::carry(
        &jar_path,
        &info,
        &work.join("mods").join(&id),
        None,
        &allowed,
        &pack,
        &conventions,
    )?;
    let content_dir = work.join("mods").join(&id).join("carry");
    let package = Package::read(&content_dir)?;
    let content = Content::read(&content_dir, &package.manifest)?;
    if content.files.is_empty() && content.blocks.is_empty() && content.items.is_empty() {
        let _ = fs::remove_dir_all(&work);
        return Err("this JAR contains no content Horizon can carry over; its Java behavior needs a native port".into());
    }

    let temp_output = output_path.with_extension("hmod.part");
    let _ = fs::remove_file(&temp_output);
    let result = zip_folder(&content_dir, &temp_output).and_then(|_| {
        let package = Package::read(&temp_output)?;
        let content = Content::read(&temp_output, &package.manifest)?;
        fs::rename(&temp_output, &output_path)
            .map_err(|e| format!("{}: {e}", output_path.display()))?;
        Ok((package, content))
    });
    let _ = fs::remove_file(&temp_output);
    let _ = fs::remove_dir_all(&work);
    let (package, content) = result?;

    println!("{}", serde_json::to_string(&json!({
        "status": "ok",
        "id": package.manifest.id,
        "name": package.manifest.name,
        "version": package.manifest.version,
        "output": output_path,
        "content_files": content.files.len(),
        "blocks_declared": content.blocks.len(),
        "items_declared": content.items.len(),
        "carry_report": {
            "copied": carried.copied,
            "recipes_upgraded": carried.recipes_upgraded,
            "recipes_kept": carried.recipes_kept.len(),
            "loot_tables_upgraded": carried.loot_tables_upgraded,
            "loot_tables_added": carried.loot_tables_added,
            "blocks_unknown": carried.blocks_unknown.len(),
            "items_unknown": carried.items_unknown.len(),
            "outside": carried.outside.len(),
            "unreadable": carried.unreadable.len(),
            "worldgen": carried.worldgen
        },
        "java_code_converted": false,
        "mixins_converted": false
    })).map_err(|e| e.to_string())?);
    Ok(())
}

fn zip_folder(root: &Path, destination: &Path) -> Result<(), String> {
    let output = File::create(destination).map_err(|e| format!("{}: {e}", destination.display()))?;
    let mut zip = ZipWriter::new(output);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    let mut paths = Vec::new();
    collect_files(root, &mut paths)?;
    paths.sort();
    for path in paths {
        let relative = path.strip_prefix(root).map_err(|e| e.to_string())?;
        let name = relative.to_str()
            .ok_or_else(|| format!("non-UTF-8 path: {}", relative.display()))?
            .replace('\\', "/");
        if name != "horizon-mod.toml" && !name.starts_with("assets/") && !name.starts_with("data/") {
            continue;
        }
        zip.start_file(&name, options).map_err(|e| e.to_string())?;
        let mut input = File::open(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        io::copy(&mut input, &mut zip).map_err(|e| e.to_string())?;
    }
    zip.finish().map_err(|e| e.to_string())?;
    Ok(())
}

fn collect_files(current: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
    let mut entries = fs::read_dir(current)
        .map_err(|e| format!("{}: {e}", current.display()))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| format!("{}: {e}", current.display()))?;
    entries.sort_by_key(|entry| entry.file_name());
    for entry in entries {
        let path = entry.path();
        let metadata = fs::symlink_metadata(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        if metadata.file_type().is_symlink() {
            return Err(format!("symlink refused in converted content: {}", path.display()));
        }
        if metadata.is_dir() {
            collect_files(&path, out)?;
        } else if metadata.is_file() {
            out.push(path);
        }
    }
    Ok(())
}
