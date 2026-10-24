use serde_json::json;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum HookKind{NativeTick,NativeWorldQuery,NativeHudCommand,NativeItemUse,Unsupported}
#[derive(Clone,Debug)]pub struct HookMapping{pub target_class:String,pub target_member:String,pub hook_id:String,pub kind:HookKind}
pub fn classify(m:&HookMapping)->Result<(),String>{if m.target_class.is_empty()||m.target_member.is_empty()||m.hook_id.is_empty(){return Err("incomplete_hook_mapping".into());}match m.kind{HookKind::Unsupported=>Err("mixin_target_not_mapped_to_native_hook".into()),_=>Ok(())}}
pub fn report()->serde_json::Value{json!({"status":"blocked","arbitrary_java_class_transformation":false,"native_rust_targets_patchable_by_mixin":false,"supported_mappings":[],"required_policy":"Required Mixins must map to an exact Java facade or versioned native hook; unresolved targets block activation."})}
#[cfg(test)]mod tests{use super::*;#[test]fn unsupported_mapping_fails_closed(){let m=HookMapping{target_class:"net.minecraft.server.MinecraftServer".into(),target_member:"tick".into(),hook_id:"".into(),kind:HookKind::Unsupported};assert_eq!(classify(&m),Err("incomplete_hook_mapping".into()));}}
