import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
tree = ET.parse("ui_now.xml")
for node in tree.iter("node"):
    text = node.get("text", "")
    desc = node.get("content-desc", "")
    if text.strip() or desc.strip():
        print(f"text={text!r} desc={desc!r} bounds={node.get('bounds')}")
