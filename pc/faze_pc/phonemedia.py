"""Uses the phone's microphone and camera on this PC.
Microphone: a virtual input called "Faze phone microphone" (PulseAudio / PipeWire): pick it as the input in any program. Needs pactl and pacat.
Camera: pictures from the phone are fed to a virtual webcam through ffmpeg when v4l2loopback is installed (sudo modprobe v4l2loopback exclusive_caps=1 card_label="Faze phone"), picked as the camera in any program.
The phone has to allow it (Settings > Devices > Let computers use my camera and microphone) and Faze has to be open on its screen."""
import glob
import os
import shutil
import subprocess
import threading
import time

SINK = "faze_phone_mic"
SOURCE = "faze_phone_mic_in"


def run(argv, timeout=10):
    try:
        r = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return 1, ""


def loopback_device(sys_video="/sys/class/video4linux"):
    """The virtual webcam made by v4l2loopback, if one is loaded."""
    for d in sorted(glob.glob(os.path.join(sys_video, "video*"))):
        try:
            name = open(os.path.join(d, "name")).read().lower()
        except OSError:
            continue
        if "loopback" in name or "faze" in name or "dummy" in name:
            return "/dev/" + os.path.basename(d)
    return None


class PhoneMedia:
    def __init__(self, request, phone, say, which=shutil.which, runner=run, popen=subprocess.Popen, sleep=time.sleep, loopback=loopback_device):
        self.request, self.phone, self.say = request, phone, say
        self.which, self.run, self.popen, self.sleep, self.loopback = which, runner, popen, sleep, loopback
        self.mic = self.cam = self.sound = False
        self.modules = []
        self.procs = {}

    def status(self):
        return {"mic": self.mic, "cam": self.cam, "sound": self.sound}

    # --- microphone
    def mic_on(self):
        if self.mic:
            return
        if not (self.which("pactl") and self.which("pacat")):
            return self.say("Microphone: pactl and pacat are needed (install pulseaudio-utils, or pipewire-pulse).", False)
        rc, mid = self.run(["pactl", "load-module", "module-null-sink", "sink_name=" + SINK, "sink_properties=device.description=Faze-phone-microphone-sink"])
        if rc != 0:
            return self.say("Microphone: the sound server would not make the virtual input.", False)
        self.modules.append(mid)
        rc, mid = self.run(["pactl", "load-module", "module-remap-source", "master=%s.monitor" % SINK, "source_name=" + SOURCE, "source_properties=device.description=Faze-phone-microphone"])
        if rc == 0:
            self.modules.append(mid)
        try:
            self.procs["mic"] = self.popen(["pacat", "--playback", "--device=" + SINK, "--rate=16000", "--format=s16le", "--channels=1"], stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError as e:
            self.mic_off()
            return self.say("Microphone: could not start the player: %s" % e, False)
        self.mic = True
        self.say('Phone microphone is on. Choose "Faze phone microphone" as the input in your program.', True)
        threading.Thread(target=self.pump_mic, daemon=True, name="phone-mic").start()

    def pump_mic(self):
        import base64
        while self.mic:
            try:
                r = self.request(self.phone, {"t": "phone_audio_get"}, 10)
            except Exception as e:
                self.fail("mic", "The phone did not answer: %s" % e)
                return
            if r.get("t") != "phone_audio_ok":
                self.fail("mic", r.get("message", "the phone sent no sound"))
                return
            data = base64.b64decode(r.get("data", ""))
            p = self.procs.get("mic")
            if not data:
                self.sleep(0.04)
                continue
            try:
                p.stdin.write(data)
                p.stdin.flush()
            except (OSError, ValueError, AttributeError):
                self.fail("mic", "The sound player stopped.")
                return

    def mic_off(self):
        was = self.mic
        self.mic = False
        p = self.procs.pop("mic", None)
        if p is not None:
            try:
                p.stdin.close()
                p.terminate()
            except Exception:
                pass
        for m in reversed(self.modules):
            self.run(["pactl", "unload-module", m])
        self.modules = []
        if was:
            try:
                self.request(self.phone, {"t": "phone_audio_stop"}, 5)
            except Exception:
                pass

    # --- the phone's own sound (what it is playing), played on this PC's speakers
    def sound_on(self):
        if self.sound:
            return
        if not self.which("pacat"):
            return self.say("Phone sound: pacat is needed (install pulseaudio-utils, or pipewire-pulse).", False)
        try:
            self.procs["sound"] = self.popen(["pacat", "--playback", "--rate=44100", "--format=s16le", "--channels=2", "--client-name=Faze-phone-sound", "--latency-msec=60"], stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError as e:
            return self.say("Phone sound: could not start the player: %s" % e, False)
        self.sound = True
        self.say("Phone sound is on: what the phone plays comes out of this PC.", True)
        threading.Thread(target=self.pump_sound, daemon=True, name="phone-sound").start()

    def pump_sound(self):
        import base64
        while self.sound:
            try:
                r = self.request(self.phone, {"t": "phone_sound_get"}, 10)
            except Exception as e:
                self.fail("sound", "The phone did not answer: %s" % e)
                return
            if r.get("t") != "phone_sound_ok":
                self.fail("sound", r.get("message", "the phone sent no sound"))
                return
            data = base64.b64decode(r.get("data", ""))
            if not data:
                self.sleep(0.03)
                continue
            try:
                p = self.procs.get("sound")
                p.stdin.write(data)
                p.stdin.flush()
            except (OSError, ValueError, AttributeError):
                self.fail("sound", "The sound player stopped.")
                return

    def sound_off(self):
        was = self.sound
        self.sound = False
        p = self.procs.pop("sound", None)
        if p is not None:
            try:
                p.stdin.close()
                p.terminate()
            except Exception:
                pass
        if was:
            try:
                self.request(self.phone, {"t": "phone_sound_stop"}, 5)
            except Exception:
                pass

    # --- camera
    def cam_on(self, facing="front"):
        if self.cam:
            return
        dev = self.loopback()
        if not dev:
            return self.say('Camera: no virtual webcam. Install v4l2loopback, then run:  sudo modprobe v4l2loopback exclusive_caps=1 card_label="Faze phone"', False)
        if not self.which("ffmpeg"):
            return self.say("Camera: ffmpeg is needed (sudo apt install ffmpeg).", False)
        try:
            self.procs["cam"] = self.popen(["ffmpeg", "-loglevel", "error", "-f", "image2pipe", "-vcodec", "mjpeg", "-i", "-", "-pix_fmt", "yuyv422", "-f", "v4l2", dev], stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
        except OSError as e:
            return self.say("Camera: could not start ffmpeg: %s" % e, False)
        self.cam = True
        self.say('Phone camera is on. Choose "Faze phone" as the camera in your program.', True)
        threading.Thread(target=self.pump_cam, args=(facing,), daemon=True, name="phone-cam").start()

    def pump_cam(self, facing):
        import base64
        while self.cam:
            try:
                r = self.request(self.phone, {"t": "phone_cam_get", "facing": facing}, 15)
            except Exception as e:
                self.fail("cam", "The phone did not answer: %s" % e)
                return
            if r.get("t") != "phone_cam_ok":
                self.fail("cam", r.get("message", "the phone sent no picture"))
                return
            try:
                p = self.procs.get("cam")
                p.stdin.write(base64.b64decode(r.get("jpeg", "")))
                p.stdin.flush()
            except (OSError, ValueError, AttributeError):
                self.fail("cam", "The virtual webcam stopped.")
                return
            self.sleep(0.05)

    def cam_off(self):
        was = self.cam
        self.cam = False
        p = self.procs.pop("cam", None)
        if p is not None:
            try:
                p.stdin.close()
                p.terminate()
            except Exception:
                pass
        if was:
            try:
                self.request(self.phone, {"t": "phone_cam_stop"}, 5)
            except Exception:
                pass

    def fail(self, which, text):
        {"mic": self.mic_off, "cam": self.cam_off, "sound": self.sound_off}[which]()
        self.say({"mic": "Microphone: ", "cam": "Camera: ", "sound": "Phone sound: "}[which] + text, False)

    def stop_all(self):
        self.mic_off()
        self.cam_off()
        self.sound_off()
