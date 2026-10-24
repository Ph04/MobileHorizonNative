//! Independent native adapter contracts for original Fabric JARs.
pub mod biome; pub mod capabilities; pub mod events; pub mod mixin; pub mod networking; pub mod registry; pub mod rendering; pub mod resources;
use serde_json::Value; use std::{sync::mpsc::{SyncSender,TrySendError},time::Duration};
pub struct NativeQuery{pub request_id:String,pub path:String,pub payload:Value,pub reply:SyncSender<Result<Value,String>>}
pub fn forward_query(path:&str,request_id:&str,payload:Value,sender:&SyncSender<NativeQuery>)->Result<Value,String>{
 if request_id.is_empty()||request_id.len()>128{return Err("invalid_request_id".into());}
 if path.is_empty()||path.len()>128||!supported_game_query(path){return Err("unsupported_query".into());}
 let(reply,rx)=std::sync::mpsc::sync_channel(1);
 match sender.try_send(NativeQuery{request_id:request_id.into(),path:path.into(),payload,reply}){Ok(())=>{},Err(TrySendError::Full(_))=>return Err("native_query_queue_full".into()),Err(TrySendError::Disconnected(_))=>return Err("native_query_queue_closed".into())}
 rx.recv_timeout(Duration::from_secs(30)).map_err(|e|match e{std::sync::mpsc::RecvTimeoutError::Timeout=>"native_query_timeout".to_owned(),std::sync::mpsc::RecvTimeoutError::Disconnected=>"native_query_reply_closed".to_owned()})?
}
fn supported_game_query(p:&str)->bool{matches!(p,"fabric.biome.catalog"|"fabric.biome.sample"|"fabric.biome.search"|"fabric.registry.biomes"|"fabric.resources.capabilities"|"fabric.mixins.capabilities"|"fabric.render.capabilities"|"fabric.networking.capabilities")}
pub fn capability_report()->Value{capabilities::report()}
#[cfg(test)]mod tests{use super::*;use serde_json::json;#[test]fn unsupported_and_full_queue_fail_closed(){let(s,_)=std::sync::mpsc::sync_channel(1);assert_eq!(forward_query("os.execute","q1",Value::Null,&s),Err("unsupported_query".into()));assert_eq!(forward_query("fabric.biome.search","",Value::Null,&s),Err("invalid_request_id".into()));let(r,_)=std::sync::mpsc::sync_channel(1);s.try_send(NativeQuery{request_id:"busy".into(),path:"fabric.biome.catalog".into(),payload:json!({}),reply:r}).unwrap();assert_eq!(forward_query("fabric.biome.search","q2",json!({}),&s),Err("native_query_queue_full".into()));}#[test]fn report_does_not_claim_java_facades(){let r=capability_report();assert_eq!(r["minecraft_java_classes"],"blocked");assert_eq!(r["world_biome_backend"],"native-backend-only");}}
