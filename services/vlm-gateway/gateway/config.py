"""与 Android 共用仓库配置；只读取公开默认值，不保存密钥。"""
import json
from pathlib import Path


CONFIG_PATH = Path(__file__).resolve().parents[3] / "config" / "cloud-models.json"
CONFIG = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
GATEWAY = CONFIG["gateway"]
PROVIDERS = CONFIG["providers"]
