//! Fabric Bridge P1 kernel. It uses namespaced IDs and never stores game-object pointers.
pub mod api_v06;
pub mod commands;
pub mod config;
pub mod events;
pub mod hud;
pub mod keys;
pub mod quota;

use std::fmt;

/// Stable identifier in `namespace:path` form.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct Id(String);

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum IdError {
    #[error("id deve ter formato namespace:path (minusculas, digitos, _ - . /): {0}")]
    Invalid(String),
}

impl Id {
    pub fn parse(s: &str) -> Result<Self, IdError> {
        let ok_ns = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '_' | '-' | '.');
        let ok_path = |c: char| ok_ns(c) || c == '/';
        match s.split_once(':') {
            Some((ns, path)) if !ns.is_empty() && !path.is_empty()
                && ns.chars().all(ok_ns) && path.chars().all(ok_path)
                && !path.split('/').any(|part| part.is_empty() || part == "." || part == "..") =>
                Ok(Id(s.to_owned())),
            _ => Err(IdError::Invalid(s.to_owned())),
        }
    }
    pub fn as_str(&self) -> &str { &self.0 }
    pub fn namespace(&self) -> &str { self.0.split_once(':').map(|x| x.0).unwrap_or("") }
}

impl fmt::Display for Id {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result { f.write_str(&self.0) }
}

#[cfg(test)]
mod tests {
    use super::Id;

    #[test]
    fn identifiers_reject_paths_that_could_be_ambiguous() {
        assert!(Id::parse("mymod:example/item").is_ok());
        assert!(Id::parse("mymod:../escape").is_err());
        assert!(Id::parse("MyMod:item").is_err());
        assert!(Id::parse("mymod:item//child").is_err());
    }
}
