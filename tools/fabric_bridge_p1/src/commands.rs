//! Typed command parser: a deliberately small, safe subset of Brigadier.
use crate::Id;
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq)]
pub enum ArgType { Bool, Int { min: i64, max: i64 }, Float, Word, Greedy, Choice(Vec<String>) }
#[derive(Clone, Debug, PartialEq)]
pub enum ArgValue { Bool(bool), Int(i64), Float(f64), Text(String) }
#[derive(Clone, Debug)]
pub struct Arg { pub name: String, pub ty: ArgType, pub optional: bool }
#[derive(Clone, Debug)]
pub struct CommandSpec { pub owner: Id, pub name: String, pub args: Vec<Arg> }

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ParseError {
    #[error("comando desconhecido: {0}")] Unknown(String),
    #[error("argumento faltando: {0}")] Missing(String),
    #[error("valor inválido para {name}: {why}")] Bad { name: String, why: String },
    #[error("argumentos extras")] Extra,
}
#[derive(Default)]
pub struct CommandRegistry { commands: BTreeMap<String, CommandSpec> }

impl CommandRegistry {
    pub fn register(&mut self, spec: CommandSpec) -> Result<(), String> {
        if spec.name.is_empty() || spec.name.len() > 64
            || !spec.name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
            return Err("nome de comando inválido; use [a-z0-9_] com até 64 caracteres".into());
        }
        let mut saw_optional = false;
        for (index, arg) in spec.args.iter().enumerate() {
            if arg.name.is_empty() || !arg.name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') {
                return Err(format!("nome de argumento inválido: {}", arg.name));
            }
            if saw_optional && !arg.optional {
                return Err("argumentos obrigatórios não podem vir depois de opcionais".into());
            }
            saw_optional |= arg.optional;
            match &arg.ty {
                ArgType::Int { min, max } if min > max => return Err(format!("faixa inválida no argumento {}", arg.name)),
                ArgType::Choice(options) if options.is_empty() || options.iter().any(|v| v.is_empty() || v.chars().any(char::is_whitespace)) =>
                    return Err(format!("opções Choice inválidas no argumento {}", arg.name)),
                ArgType::Greedy if index + 1 != spec.args.len() => return Err("Greedy deve ser o último argumento".into()),
                _ => {}
            }
        }
        if self.commands.contains_key(&spec.name) {
            return Err(format!("comando duplicado: {}", spec.name));
        }
        self.commands.insert(spec.name.clone(), spec);
        Ok(())
    }

    /// Parse a command line with its slash already removed.
    pub fn parse(&self, line: &str) -> Result<(Id, Vec<(String, ArgValue)>), ParseError> {
        let line = line.trim();
        let (name, mut rest) = line.split_once(char::is_whitespace).unwrap_or((line, ""));
        let spec = self.commands.get(name).ok_or_else(|| ParseError::Unknown(name.into()))?;
        let mut output = Vec::new();
        for arg in &spec.args {
            rest = rest.trim_start();
            if rest.is_empty() {
                if arg.optional { continue; }
                return Err(ParseError::Missing(arg.name.clone()));
            }
            let (token, tail) = if arg.ty == ArgType::Greedy {
                (rest, "")
            } else {
                rest.split_once(char::is_whitespace).unwrap_or((rest, ""))
            };
            let bad = |why: &str| ParseError::Bad { name: arg.name.clone(), why: why.into() };
            let value = match &arg.ty {
                ArgType::Bool => ArgValue::Bool(token.parse().map_err(|_| bad("esperado true/false"))?),
                ArgType::Int { min, max } => {
                    let n: i64 = token.parse().map_err(|_| bad("esperado inteiro"))?;
                    if n < *min || n > *max { return Err(bad(&format!("fora de [{min}, {max}]"))); }
                    ArgValue::Int(n)
                }
                ArgType::Float => {
                    let n: f64 = token.parse().map_err(|_| bad("esperado número"))?;
                    if !n.is_finite() { return Err(bad("número deve ser finito")); }
                    ArgValue::Float(n)
                }
                ArgType::Word | ArgType::Greedy => ArgValue::Text(token.to_owned()),
                ArgType::Choice(options) => {
                    if !options.iter().any(|option| option == token) {
                        return Err(bad(&format!("use uma das opções: {options:?}")));
                    }
                    ArgValue::Text(token.to_owned())
                }
            };
            output.push((arg.name.clone(), value));
            rest = tail;
        }
        if !rest.trim().is_empty() { return Err(ParseError::Extra); }
        Ok((spec.owner.clone(), output))
    }

    pub fn suggest(&self, name: &str, arg_index: usize) -> Vec<String> {
        match self.commands.get(name).and_then(|s| s.args.get(arg_index)).map(|a| &a.ty) {
            Some(ArgType::Choice(options)) => options.clone(),
            Some(ArgType::Bool) => vec!["true".into(), "false".into()],
            _ => Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> CommandRegistry {
        let mut registry = CommandRegistry::default();
        registry.register(CommandSpec {
            owner: Id::parse("demo:main").unwrap(),
            name: "zoom".into(),
            args: vec![
                Arg { name: "level".into(), ty: ArgType::Int { min: 1, max: 10 }, optional: false },
                Arg { name: "note".into(), ty: ArgType::Greedy, optional: true },
            ],
        }).unwrap();
        registry
    }

    #[test]
    fn parses_typed_args_and_greedy_tail() {
        let (_, args) = sample().parse("zoom 3 olha só").unwrap();
        assert_eq!(args[0].1, ArgValue::Int(3));
        assert_eq!(args[1].1, ArgValue::Text("olha só".into()));
        assert!(matches!(sample().parse("zoom 99"), Err(ParseError::Bad { .. })));
    }

    #[test]
    fn rejects_non_finite_floats_and_bad_definitions() {
        let mut r = CommandRegistry::default();
        r.register(CommandSpec {
            owner: Id::parse("demo:main").unwrap(),
            name: "set".into(),
            args: vec![Arg { name: "value".into(), ty: ArgType::Float, optional: false }],
        }).unwrap();
        assert!(matches!(r.parse("set NaN"), Err(ParseError::Bad { .. })));
        assert!(r.register(CommandSpec {
            owner: Id::parse("demo:other").unwrap(), name: "invalid".into(),
            args: vec![Arg { name: "x".into(), ty: ArgType::Int { min: 5, max: 1 }, optional: false }],
        }).is_err());
    }
}
