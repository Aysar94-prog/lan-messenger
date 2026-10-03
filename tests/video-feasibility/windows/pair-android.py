"""R03/R04 test-only USB-controlled LAN media evidence. Never forwards media over adb."""
import argparse
import base64
import json
import pathlib
import queue
import re
import subprocess
import threading
import time
import uuid

APP = "net.lanmsg.chat.videofeasibility"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dll", required=True)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--audio-only", action="store_true")
    parser.add_argument("--audio-observation-seconds", type=int, default=0,
                        help="Human listening window 0..120 seconds; not automatic audible acceptance")
    parser.add_argument("--caller", choices=("windows", "android"), default="windows")
    parser.add_argument("--upgrade-origin", choices=("windows", "android"), default="android")
    parser.add_argument("--rollback-check", action="store_true")
    parser.add_argument("--mechanism", choices=("reoffer", "separate"), default="reoffer")
    parser.add_argument("--output-root", default=r"D:\LAN-Messenger\outputs\.build\video-feasibility\evidence")
    args = parser.parse_args()
    if not 0 <= args.audio_observation_seconds <= 120:
        parser.error("Audio observation window must be within 0..120 seconds")
    root = pathlib.Path(args.output_root) / ("paired-" + uuid.uuid4().hex)
    root.mkdir(parents=True)
    evidence = {"scope": "Windows-native/physical-Android generated LAN test; audible/camera acceptance separate", "passed": False}
    responses = queue.Queue()
    process = None
    separate_ready = False
    usb_verified = False
    log = (root / "native-stderr.log").open("w", encoding="utf-8")

    def adb(*arguments, check=True):
        return subprocess.run([args.adb, "-s", args.serial, *arguments], text=True,
                              encoding="utf-8", errors="replace", capture_output=True, timeout=35, check=check)

    def android(command, **fields):
        request = uuid.uuid4().hex
        extras = {"request": request, "cmd": command, "node": "a", "codec": "VP8", "mode": "audio", "source": "generated", **fields}
        if "sdp" in extras:
            encoded = extras["sdp"].encode("utf-8")
            if len(encoded) > 49152:
                raise ValueError("SDP exceeds Android test bound")
            extras["sdp"] = base64.b64encode(encoded).decode("ascii")
        command_args = ["shell", "am", "start", "-W", "-n", APP + "/.HarnessActivity"]
        for key, value in extras.items():
            command_args += ["--es", key, value]
        adb(*command_args)
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            reply = adb("shell", "run-as", APP, "cat", f"files/{request}.json", check=False)
            if reply.returncode == 0:
                value = json.loads(reply.stdout)
                (root / f"{request}-{command}.json").write_text(json.dumps(value, indent=2), encoding="utf-8")
                if not value.get("ok"):
                    raise RuntimeError(value.get("error", "Android command failed"))
                return value
            time.sleep(0.25)
        raise TimeoutError("Android command timeout: " + command)

    def windows(command, payload="", node="a"):
        process.stdin.write(json.dumps({"cmd": command, "node": node, "payload": payload}) + "\n")
        process.stdin.flush()
        value = responses.get(timeout=30)
        if not value.get("ok"):
            raise RuntimeError(value.get("error", "Native command failed"))
        return value.get("value", "")

    def reader():
        for line in process.stdout:
            try:
                value = json.loads(line)
                responses.put(value)
            except ValueError:
                pass
        responses.put({"ok": False, "error": "Native driver ended"})

    def gathered(node="a"):
        deadline = time.monotonic() + 12
        while "gathering=2" not in windows("state", node=node):
            if time.monotonic() >= deadline:
                raise TimeoutError("Native gathering")
            time.sleep(0.1)
        return windows("sdp", node=node)

    def sample():
        raw = json.loads(windows("stats"))
        android_stats = android("stats")["stats"]
        sink = json.loads(windows("counters"))
        if separate_ready:
            raw += json.loads(windows("stats", node="b"))
            video_stats = android("stats", node="b")["stats"]
            android_stats = {**video_stats, "rtp": android_stats["rtp"] + video_stats["rtp"]}
            sink = json.loads(windows("counters", node="b"))
        return {"windows": raw, "windowsSink": sink,
                "windowsAudioStatus": windows("audio-status"), "android": android_stats}

    def rtp(platform, kind, key):
        values = platform if isinstance(platform, list) else platform.get("rtp", [])
        return sum(int(s.get(key, 0)) for s in values if s.get("type") in ("inbound-rtp", "outbound-rtp")
                   and s.get("kind", s.get("mediaType")) == kind)

    def audio_progress(current, before=None):
        for platform in ("windows", "android"):
            for key in ("packetsSent", "packetsReceived"):
                prior = 0 if before is None else rtp(before[platform], "audio", key)
                if rtp(current[platform], "audio", key) <= prior:
                    raise AssertionError(f"{platform} G722 audio did not advance: {key}")

    try:
        devices = subprocess.run([args.adb, "devices", "-l"], capture_output=True, text=True, check=True).stdout
        matching = [line for line in devices.splitlines() if line.split() and line.split()[0] == args.serial]
        if len(matching) != 1 or " device " not in matching[0] or ":" in args.serial:
            raise RuntimeError("An online USB device is required; no network adb")
        usb_verified = True
        evidence["productionBefore"] = adb("shell", "dumpsys", "package", "net.lanmsg.chat").stdout
        adb("shell", "pm", "path", APP)
        process = subprocess.Popen(["dotnet", args.driver, args.dll, "--endpoint"], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=log, text=True, encoding="utf-8", bufsize=1)
        threading.Thread(target=reader, daemon=True).start()
        if not responses.get(timeout=20).get("ready"):
            raise RuntimeError("Native driver startup")
        windows("create")
        android("stop")
        android("init")
        if args.caller == "windows":
            windows("offer")
            offer = gathered()
        else:
            offer = android("offer")["sdp"]
        if "m=video" in offer or "G722/8000" not in offer or "a=fingerprint:" not in offer:
            raise AssertionError("Native voice-only SDP is not secure G722")
        if args.caller == "windows":
            answer = android("answer", sdp=offer)["sdp"]
        else:
            windows("set-offer", offer)
            windows("answer")
            answer = gathered()
        if "m=video" in answer or "G722/8000" not in answer:
            raise AssertionError("Android did not accept camera-free G722")
        evidence["voiceSdpDirections"] = {
            "offer": [s for s in offer.splitlines() if s in ("a=sendrecv", "a=recvonly", "a=sendonly", "a=inactive")],
            "answer": [s for s in answer.splitlines() if s in ("a=sendrecv", "a=recvonly", "a=sendonly", "a=inactive")],
        }
        if args.caller == "windows": windows("set-answer", answer)
        else: android("remote", sdp=answer)
        deadline = time.monotonic() + 15
        while "connection=2" not in windows("state"):
            if time.monotonic() >= deadline:
                raise TimeoutError("Native peer connection not connected")
            time.sleep(0.1)
        windows("start-audio")
        time.sleep(4)
        evidence["voiceBefore"] = sample()
        audio_progress(evidence["voiceBefore"])
        print("PASS: paired secure G722 packets both ways before video", flush=True)
        if args.audio_observation_seconds:
            print("LISTEN NOW: speak into the PC, then the phone; confirm sound at the other device.", flush=True)
            time.sleep(args.audio_observation_seconds)
            evidence["afterListeningWindow"] = sample()
            audio_progress(evidence["afterListeningWindow"], evidence["voiceBefore"])
            evidence["audibleAcceptance"] = "Pending explicit human observation; not inferred from counters"
        if args.rollback_check:
            if args.mechanism == "separate":
                windows("create-video", node="b")
                windows("video", node="b")
                windows("offer", node="b")
                windows("rollback", node="b")
                windows("destroy", node="b")
            else:
                windows("video")
                windows("offer")
                windows("rollback")
            try:
                windows("set-offer", "not SDP")
                raise AssertionError("Malformed SDP accepted")
            except RuntimeError as error:
                if "Invalid remote SDP" not in str(error):
                    raise
            time.sleep(3)
            evidence["afterRollback"] = sample()
            audio_progress(evidence["afterRollback"], evidence["voiceBefore"])
            evidence["rollbackPassed"] = True
            print("PASS: malformed SDP rejection and local video-offer rollback preserve paired G722", flush=True)
        if not args.audio_only:
            if args.mechanism == "separate":
                windows("create-video", node="b")
                android("init", node="b", mode="video-only")
                windows("video", node="b")
                if args.upgrade_origin == "windows":
                    windows("offer", node="b")
                    offer = gathered("b")
                    if "m=audio" in offer or "a=fingerprint:" not in offer:
                        raise AssertionError("Separate video connection is not video-only secure SDP")
                    answer = android("answer", node="b", sdp=offer)["sdp"]
                    windows("set-answer", answer, node="b")
                else:
                    offer = android("offer", node="b")["sdp"]
                    if "m=audio" in offer or "a=fingerprint:" not in offer:
                        raise AssertionError("Separate Android video connection is not video-only secure SDP")
                    windows("set-offer", offer, node="b")
                    windows("answer", node="b")
                    android("remote", node="b", sdp=gathered("b"))
                separate_ready = True
            # Android upgrade starts only a generated source; native callee adds its source after remote offer.
            elif args.upgrade_origin == "android":
                offer = android("upgrade")["sdp"]
                windows("set-offer", offer)
                windows("video")
                windows("answer")
                android("remote", sdp=gathered())
            else:
                windows("video")
                windows("offer")
                answer = android("answer", sdp=gathered())["sdp"]
                windows("set-answer", answer)
            time.sleep(6)
            evidence["videoAfter"] = sample()
            audio_progress(evidence["videoAfter"], evidence["voiceBefore"])
            for platform in ("windows", "android"):
                for counter in ("packetsSent", "packetsReceived", "framesEncoded", "framesDecoded"):
                    if rtp(evidence["videoAfter"][platform], "video", counter) <= 0:
                        raise AssertionError(platform + " missing video counter " + counter)
            for sink in (evidence["videoAfter"]["windowsSink"], evidence["videoAfter"]["android"]):
                if sink["decodedSinkFrames"] < 10 or sink["decodedMotionChanges"] < 5:
                    raise AssertionError("Paired decoded moving video missing")
            video_node = "b" if separate_ready else "a"
            windows("video-off", node=video_node)
            android("camera-off", node=video_node)
            time.sleep(3)
            evidence["videoOff"] = sample()
            audio_progress(evidence["videoOff"], evidence["videoAfter"])
            if separate_ready:
                windows("destroy", node="b")
                android("stop-node", node="b")
                separate_ready = False
                time.sleep(2)
                evidence["videoDisposed"] = sample()
                audio_progress(evidence["videoDisposed"], evidence["videoOff"])
                android("init", node="b", mode="video-only")
                try:
                    android("answer", node="b", sdp="not SDP")
                    raise AssertionError("Android accepted malformed isolated video SDP")
                except RuntimeError as error:
                    if "Invalid/oversized SDP" not in str(error):
                        raise
                time.sleep(2)
                evidence["afterAndroidVideoFailure"] = sample()
                audio_progress(evidence["afterAndroidVideoFailure"], evidence["videoDisposed"])
                evidence["androidVideoFailurePreservedVoice"] = True
            print("PASS: paired moving VP8 and continuing G722 after upgrade and video-off", flush=True)
        evidence["passed"] = True
        evidence["caller"] = args.caller
        evidence["upgradeOrigin"] = args.upgrade_origin
        evidence["mechanism"] = args.mechanism
    except Exception as error:
        evidence["failure"] = type(error).__name__ + ": " + str(error)
        print("FAIL: " + evidence["failure"], flush=True)
    finally:
        try:
            if process and process.poll() is None:
                windows("stop-audio")
                windows("destroy")
        except Exception as error:
            evidence["nativeCleanupError"] = str(error)
            evidence["passed"] = False
        if process:
            process.stdin.close()
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                process.kill()  # Only this owned test driver; never a broad process kill.
                process.wait()
                evidence["passed"] = False
                evidence["nativeCleanupError"] = "Driver shutdown timeout"
        if usb_verified:
            try:
                android("stop")
                adb("shell", "am", "force-stop", APP)
                evidence["productionAfter"] = adb("shell", "dumpsys", "package", "net.lanmsg.chat").stdout
            except Exception as error:
                evidence["androidCleanupError"] = str(error)
                evidence["passed"] = False
        package_fields = r"(?:versionCode=\d+|versionName=[^\r\n]+|firstInstallTime=[^\r\n]+|lastUpdateTime=[^\r\n]+)"
        if evidence.get("productionBefore") and evidence.get("productionAfter"):
            evidence["productionPackageUnchanged"] = re.findall(package_fields, evidence["productionBefore"]) == re.findall(package_fields, evidence["productionAfter"])
            if not evidence["productionPackageUnchanged"]:
                evidence["passed"] = False
        (root / "result.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        log.close()
        print("EVIDENCE=" + str(root / "result.json"), flush=True)
    return 0 if evidence["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
