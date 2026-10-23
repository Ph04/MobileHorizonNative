//! Per-mod typed configuration with validated schema and bounded values; no filesystem access.
use crate::Id;
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq)]
pub enum Value { Bool(bool), Int(i64), Float(f64), Text(String) }
#[derive(Clone, Debug)]
pub struct Field { pub default: Value, pub min: Option<f64>, pub max: Option<f64> }
pub const MAX_KEYS: usize = 128;
pub const MAX_TEXT: usize = 1024;
const MAX_KEY_LENGTH: usize = 64;

#[derive(Default)]
pub struct ModConfig {
    owner: Option<Id>,
    schema: BTreeMap<String, Field>,
    values: BTreeMap<String, Value>,
}
impl ModConfig {
    pub fn new(owner: Id) -> Self { Self { owner: Some(owner), ..Self::default() } }

    pub fn declare(&mut self, key: &str, field: Field) -> Result<(), String> {
        if !valid_key(key) { return Err("chave inválida; use [a-z0-9_.-]".into()); }
        if self.schema.contains_key(key) { return Err(format!("chave já declarada: {key}")); }
        if self.schema.len() >= MAX_KEYS { return Err(format!("limite de {MAX_KEYS} chaves")); }
        if field.min.is_some_and(|v| !v.is_finite()) || field.max.is_some_and(|v| !v.is_finite())
            || matches!((field.min, field.max), (Some(min), Some(max)) if min > max) {
            return Err("faixa de configuração inválida".into());
        }
        validate_value(&field, &field.default)?;
        self.schema.insert(key.into(), field);
        Ok(())
    }

    pub fn get(&self, key: &str) -> Option<&Value> {
        self.values.get(key).or_else(|| self.schema.get(key).map(|field| &field.default))
    }

    pub fn set(&mut self, key: &str, value: Value) -> Result<(), String> {
        let field = self.schema.get(key).ok_or("chave não declarada")?;
        validate_value(field, &value)?;
        self.values.insert(key.into(), value);
        Ok(())
    }
    pub fn owner(&self) -> Option<&Id> { self.owner.as_ref() }
}

fn valid_key(key: &str) -> bool {
    !key.is_empty() && key.len() <= MAX_KEY_LENGTH
        && key.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b"_.-".contains(&b))
}

fn validate_value(field: &Field, value: &Value) -> Result<(), String> {
    if std::mem::discriminant(&field.default) != std::mem::discriminant(value) {
        return Err("tipo diferente do declarado".into());
    }
    let numeric = match value {
        Value::Int(i) => Some(*i as f64),
        Value::Float(f) if f.is_finite() => Some(*f),
        Value::Float(_) => return Err("número deve ser finito".into()),
        _ => None,
    };
    if let Some(number) = numeric {
        if field.min.is_some_and(|min| number < min) || field.max.is_some_and(|max| number > max) {
            return Err("valor fora dos limites".into());
        }
    }
    if let Value::Text(text) = value {
        if text.len() > MAX_TEXT { return Err("texto muito longo".into()); }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_schema_type_ranges_and_nan() {
        let mut config = ModConfig::new(Id::parse("demo:main").unwrap());
        config.declare("scale", Field { default: Value::Float(1.0), min: Some(0.25), max: Some(3.0) }).unwrap();
        assert!(config.set("scale", Value::Float(f64::NAN)).is_err());
        assert!(config.set("scale", Value::Float(4.0)).is_err());
        assert!(config.set("scale", Value::Bool(true)).is_err());
        assert!(config.declare("../escape", Field { default: Value::Bool(false), min: None, max: None }).is_err());
    }
}
