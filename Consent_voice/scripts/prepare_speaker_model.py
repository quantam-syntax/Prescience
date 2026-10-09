"""Generate the CMN-enabled WeSpeaker asset, preserving the upstream model.

This release lacks feature_normalize_type metadata and its graph does not
subtract the input feature mean. WeSpeaker's reference infer_onnx.py does.
Sherpa supports that operation through feature_normalize_type=global-mean.
Only a ModelProto metadata entry is appended; weights are unchanged.
"""
import hashlib
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "app/src/main/assets/wespeaker_en_voxceleb_resnet34.onnx"
target = source.with_name("wespeaker_en_voxceleb_resnet34_cmn_v1.onnx")
data = source.read_bytes()
expected = "5ef208a9da1453335308a6b6f4e6dfbd7e183a38b604de0a57664f45d257fe94"
if hashlib.sha256(data).hexdigest() != expected:
    raise ValueError("Unexpected source model; review its graph and metadata first")
key = b"feature_normalize_type"
value = b"global-mean"
entry = b"\x0a" + bytes([len(key)]) + key + b"\x12" + bytes([len(value)]) + value
# ONNX ModelProto field 14 (metadata_props), length-delimited StringStringEntryProto.
target.write_bytes(data + b"\x72" + bytes([len(entry)]) + entry)
print(f"Generated {target.name}: {hashlib.sha256(target.read_bytes()).hexdigest()}")
