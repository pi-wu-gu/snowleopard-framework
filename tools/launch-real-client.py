"""
启动真实客户端（PCL2 / 官方启动器均可），用于真机验收——不经过启动器界面。

## 为什么需要它

`gradlew runClient` 是 dev 客户端：测不出 **jarJar 内嵌**、真实资源加载、真实 FML 装配。
而 GUI 自动化在开发机上被沙箱拦住（`Add-Type` / COM 都被拒），无法点"启动游戏"。
所以按版本 JSON 自己拼命令行——这正是启动器在做的事。

## 三个已踩过的坑（都写进代码了）

1. **首次运行的辅助功能引导屏会挡住一切**
   `Welcome to Minecraft / Narrator: OFF / Continue` 那道屏不是标题界面，quickPlay 与任何
   "等标题界面再连"的逻辑都不会触发——表现为"客户端起来了但永远不进服"，且日志停在
   `Loaded 0 entity animations`（`jstack` 显示 Render thread 正常在 `limitDisplayFPS`，不是卡死）。
   → 版本目录的 `options.txt` 必须有 `onboardAccessibility:false`（本脚本会检查并提示）。
2. **PCL2 的扁平化版本 JSON 里同一条库会出现两次**（gson / guava / commons-lang3），
   `BootstrapLauncher` 按路径建映射 → `IllegalStateException: Duplicate key`。
   → classpath 必须去重。
3. **父进程一退出，沙箱会把子进程一起回收**（日志 0 行、进程瞬间消失）。
   → 用 `--wait` 让脚本守着进程，别用"spawn 完就走"。

## 用法

    python tools/launch-real-client.py --root "C:/Users/me/PCL2/.minecraft" --version 雪豹测试 --join --wait

    --root     游戏根目录（含 libraries/ versions/ assets/）；也可用环境变量 MC_ROOT
    --version  版本目录名（versions/<name>/<name>.json）
    --java     java 可执行文件（默认 C:/Program Files/Java/jdk-21/bin/java.exe）
    --join     启动后自动连 localhost:25565（quickPlay + 模组自连双保险）
    --wait     守着进程直到退出（后台任务里必须加，否则会被回收）
"""
import argparse
import json
import os
import subprocess
import sys
import uuid
import zipfile
from pathlib import Path

DEFAULT_JAVA = r"C:\Program Files\Java\jdk-21\bin\java.exe"
SERVER_ADDR = "localhost:25565"


def allows_windows(obj) -> bool:
    """按 rules 判断该库在当前系统是否可用（无 rules = 全平台）。"""
    rules = obj.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        os_rule = r.get("os") or {}
        name = os_rule.get("name")
        arch = os_rule.get("arch")
        if (name is None or name == "windows") and (arch is None or arch == "x86_64"):
            ok = r.get("action") == "allow"
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.environ.get("MC_ROOT", ""))
    ap.add_argument("--version", required=True)
    ap.add_argument("--java", default=DEFAULT_JAVA)
    ap.add_argument("--join", action="store_true")
    ap.add_argument("--wait", action="store_true")
    ap.add_argument("--log", default="")
    a = ap.parse_args()

    if not a.root:
        print("[error] 需要 --root（游戏根目录）或环境变量 MC_ROOT", file=sys.stderr)
        return 2
    mc_root = Path(a.root)
    ver_dir = mc_root / "versions" / a.version
    vjson_path = ver_dir / f"{a.version}.json"
    if not vjson_path.exists():
        print(f"[error] 找不到版本 JSON: {vjson_path}", file=sys.stderr)
        return 2

    opts = ver_dir / "options.txt"
    if not opts.exists() or "onboardAccessibility:false" not in opts.read_text(
            encoding="utf-8", errors="replace"):
        print("[warn] options.txt 缺少 onboardAccessibility:false —— 客户端会停在辅助功能引导屏，"
              "此时 quickPlay 与自连都不会触发（见文件头坑 1）")

    vjson = json.loads(vjson_path.read_text(encoding="utf-8"))
    lib_root = mc_root / "libraries"
    natives_dir = ver_dir / "natives"
    natives_dir.mkdir(parents=True, exist_ok=True)

    classpath, missing = [], []
    for lib in vjson["libraries"]:
        if not allows_windows(lib):
            continue
        artifact = (lib.get("downloads") or {}).get("artifact")
        if not artifact:
            continue
        path = lib_root / artifact["path"]
        if not path.exists():
            missing.append(str(path))
            continue
        name = lib.get("name") or ""
        if "natives-windows" in name and "arm64" not in name:
            with zipfile.ZipFile(path) as z:
                z.extractall(natives_dir)
        else:
            classpath.append(str(path))

    # 坑 2：去重（PCL2 的扁平化 JSON 有重复库，BootstrapLauncher 会炸）
    seen, dedup = set(), []
    for c in classpath:
        key = os.path.normcase(os.path.abspath(c))
        if key not in seen:
            seen.add(key)
            dedup.append(c)
    classpath = dedup + [str(ver_dir / f"{a.version}.jar")]

    if missing:
        print("[warn] 缺失库（可能是被启动器清理过），先补全再启动：")
        for m in missing[:8]:
            print("   ", m)

    subs = {
        "natives_directory": str(natives_dir),
        "launcher_name": "snowleopard-realclient",
        "launcher_version": "1.0",
        "classpath": ";".join(classpath),
        "classpath_separator": ";",
        "library_directory": str(lib_root),
        "version_name": a.version,
        "game_directory": str(ver_dir),
        "assets_root": str(mc_root / "assets"),
        "assets_index_name": (vjson.get("assetIndex") or {}).get("id", "17"),
        "auth_player_name": "SnowTester",
        "auth_uuid": str(uuid.uuid5(uuid.NAMESPACE_DNS, "snowtester")).replace("-", ""),
        "auth_access_token": "0",
        "clientid": "0",
        "auth_xuid": "0",
        "user_type": "legacy",
        "version_type": "release",
        "resolution_width": "1280",
        "resolution_height": "720",
    }

    def expand(token: str) -> str:
        out = token
        for k, v in subs.items():
            out = out.replace("${" + k + "}", v)
        return out

    jvm = [expand(x) for x in vjson["arguments"]["jvm"] if isinstance(x, str)]
    for x in vjson["arguments"]["jvm"]:
        if isinstance(x, dict) and allows_windows(x) and isinstance(x.get("value"), str):
            jvm.append(expand(x["value"]))

    game = []
    for x in vjson["arguments"]["game"]:
        if isinstance(x, str):
            game.append(expand(x))
        elif isinstance(x, dict) and x.get("rules") and \
                all("features" not in r for r in x["rules"]) and allows_windows(x) \
                and isinstance(x.get("value"), str):
            game.append(expand(x["value"]))

    jvm = ["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"] + jvm
    if a.join:
        game += ["--quickPlayMultiplayer", SERVER_ADDR]
        jvm = [f"-Dsnowleopard.autoconnect={SERVER_ADDR}"] + jvm

    cmd = [a.java, "-Xmx2G", "-XX:+UseG1GC"] + jvm + [vjson["mainClass"]] + game
    log = Path(a.log) if a.log else ver_dir / "logs" / "launch-stdout.log"
    log.parent.mkdir(parents=True, exist_ok=True)
    print(f"[launch] {len(classpath)} jar · natives → {natives_dir}")
    print(f"[launch] stdout → {log}")
    with open(log, "wb") as f:
        f.write(("CMD: " + " ".join(cmd) + "\n\n").encode("utf-8"))
        f.flush()
        proc = subprocess.Popen(cmd, cwd=str(ver_dir), stdout=f, stderr=subprocess.STDOUT)
    print(f"[launch] pid={proc.pid}")
    if a.wait:
        rc = proc.wait()   # 坑 3：必须守着，否则子进程被回收
        print(f"[launch] exited rc={rc}")
        return rc
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
