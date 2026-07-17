(function () {
    "use strict";

    var clockScreen = document.getElementById("clock-screen");
    var weatherScreen = document.getElementById("weather-screen");
    var mediaScreen = document.getElementById("media-screen");
    var clock = document.getElementById("nixie-clock");
    var video = document.getElementById("camera-preview");
    var canvas = document.getElementById("sample-canvas");
    var mediaStatus = document.getElementById("media-status");
    var imagePresence = document.getElementById("image-presence");
    var imageDigest = document.getElementById("image-digest");
    var imageChange = document.getElementById("image-change");
    var audioPresence = document.getElementById("audio-presence");
    var audioDigest = document.getElementById("audio-digest");
    var audioChange = document.getElementById("audio-change");
    var audioLevel = document.getElementById("audio-level");
    var lastTime = "";
    var activeScreen = "clock";
    var mediaStarting = false;
    var mediaStream = null;
    var mediaTimer = null;
    var audioContext = null;
    var analyser = null;
    var previousFrame = null;
    var previousSpectrum = null;
    var previousSound = false;

    function pad(value) {
        return value < 10 ? "0" + value : String(value);
    }

    function tube(character) {
        if (character === ":") return '<span class="colon"><i></i><i></i></span>';
        return '<span class="tube"><i class="mesh"></i><b>' + character + '</b><i class="base"></i></span>';
    }

    function updateClock() {
        var now = new Date();
        var value = pad(now.getHours()) + ":" + pad(now.getMinutes()) + ":" + pad(now.getSeconds());
        var html = "";
        var i;
        if (value === lastTime) return;
        lastTime = value;
        for (i = 0; i < value.length; i++) html += tube(value.charAt(i));
        clock.innerHTML = html;
    }

    function hashValues(values) {
        var hash = 2166136261;
        var i;
        for (i = 0; i < values.length; i++) {
            hash ^= values[i] & 255;
            hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24);
            hash |= 0;
        }
        return ("00000000" + (hash >>> 0).toString(16)).slice(-8);
    }

    function stopMedia() {
        var tracks;
        var i;
        mediaStarting = false;
        if (mediaTimer) {
            clearInterval(mediaTimer);
            mediaTimer = null;
        }
        if (mediaStream) {
            tracks = mediaStream.getTracks ? mediaStream.getTracks() : [];
            for (i = 0; i < tracks.length; i++) tracks[i].stop();
            mediaStream = null;
        }
        if (audioContext && audioContext.close) {
            try { audioContext.close(); } catch (ignored) { }
        }
        audioContext = null;
        analyser = null;
        previousFrame = null;
        previousSpectrum = null;
        previousSound = false;
        try { video.srcObject = null; } catch (ignoredObject) { video.removeAttribute("src"); }
    }

    function attachMedia(stream) {
        var AudioContextClass;
        var source;
        mediaStream = stream;
        mediaStarting = false;
        if ("srcObject" in video) {
            video.srcObject = stream;
        } else {
            video.src = (window.URL || window.webkitURL).createObjectURL(stream);
        }
        try { video.play(); } catch (ignoredPlay) { }

        AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (AudioContextClass && stream.getAudioTracks && stream.getAudioTracks().length) {
            try {
                audioContext = new AudioContextClass();
                source = audioContext.createMediaStreamSource(stream);
                analyser = audioContext.createAnalyser();
                analyser.fftSize = 256;
                analyser.smoothingTimeConstant = 0.35;
                source.connect(analyser);
                if (audioContext.resume) audioContext.resume();
            } catch (audioError) {
                analyser = null;
                audioPresence.textContent = "麦克风：有；音频分析不可用";
            }
        }
        mediaStatus.textContent = "本机实时采样中";
        sampleMedia();
        mediaTimer = setInterval(sampleMedia, 750);
    }

    function mediaFailed(error) {
        mediaStarting = false;
        mediaStatus.textContent = "媒体启动失败：" + (error && (error.name || error.message) || "未知错误");
        imagePresence.textContent = "摄像头：无图像或权限被拒绝";
        audioPresence.textContent = "麦克风：无音频或权限被拒绝";
    }

    function startMedia() {
        var legacyGetUserMedia;
        if (mediaStream || mediaStarting || activeScreen !== "media") return;
        mediaStarting = true;
        mediaStatus.textContent = "正在请求摄像头和麦克风权限…";
        if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
            navigator.mediaDevices.getUserMedia({ video: true, audio: true }).then(attachMedia, mediaFailed);
            return;
        }
        legacyGetUserMedia = navigator.getUserMedia || navigator.webkitGetUserMedia || navigator.mozGetUserMedia;
        if (legacyGetUserMedia) {
            legacyGetUserMedia.call(navigator, { video: true, audio: true }, attachMedia, mediaFailed);
        } else {
            mediaFailed({ name: "当前 WebView 不支持 getUserMedia" });
        }
    }

    function sampleImage() {
        var context;
        var pixels;
        var values = [];
        var total = 0;
        var difference = 0;
        var count = 0;
        var i;
        var luminance;
        var changed;
        if (!mediaStream || !mediaStream.getVideoTracks || !mediaStream.getVideoTracks().length) {
            imagePresence.textContent = "摄像头：没有视频轨道";
            return;
        }
        if (video.readyState < 2 || !video.videoWidth) {
            imagePresence.textContent = "摄像头：有；等待首帧";
            return;
        }
        try {
            context = canvas.getContext("2d");
            context.drawImage(video, 0, 0, canvas.width, canvas.height);
            pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
            for (i = 0; i < pixels.length; i += 4) {
                luminance = Math.round((pixels[i] * 30 + pixels[i + 1] * 59 + pixels[i + 2] * 11) / 100);
                values.push(Math.round(luminance / 16));
                total += luminance;
                if (previousFrame) difference += Math.abs(luminance - previousFrame[count]);
                count++;
            }
            changed = previousFrame !== null && difference / count >= 5;
            imagePresence.textContent = "摄像头：有；图像：有；平均亮度：" + Math.round(total / count / 255 * 100) + "%";
            imageDigest.textContent = "图像摘要：" + hashValues(values);
            imageChange.textContent = "相对上一采样：" + (previousFrame === null ? "建立基线" : changed ? "有变化" : "基本稳定");
            imageChange.className = changed ? "changed" : "stable";
            previousFrame = [];
            for (i = 0; i < pixels.length; i += 4) {
                previousFrame.push(Math.round((pixels[i] * 30 + pixels[i + 1] * 59 + pixels[i + 2] * 11) / 100));
            }
        } catch (error) {
            imagePresence.textContent = "图像采样失败：" + error.message;
        }
    }

    function sampleAudio() {
        var timeData;
        var spectrum;
        var values = [];
        var sum = 0;
        var spectrumDifference = 0;
        var i;
        var centered;
        var level;
        var sound;
        var changed;
        if (!mediaStream || !mediaStream.getAudioTracks || !mediaStream.getAudioTracks().length) {
            audioPresence.textContent = "麦克风：没有音频轨道";
            audioLevel.style.width = "0";
            return;
        }
        if (!analyser) {
            audioPresence.textContent = "麦克风：有；等待音频分析器";
            return;
        }
        timeData = new Uint8Array(analyser.fftSize);
        spectrum = new Uint8Array(analyser.frequencyBinCount);
        analyser.getByteTimeDomainData(timeData);
        analyser.getByteFrequencyData(spectrum);
        for (i = 0; i < timeData.length; i++) {
            centered = (timeData[i] - 128) / 128;
            sum += centered * centered;
        }
        level = Math.min(100, Math.round(Math.sqrt(sum / timeData.length) * 260));
        sound = level >= 3;
        for (i = 0; i < spectrum.length; i += 4) {
            values.push(Math.round((spectrum[i] + spectrum[i + 1] + spectrum[i + 2] + spectrum[i + 3]) / 64));
        }
        if (previousSpectrum) {
            for (i = 0; i < values.length; i++) spectrumDifference += Math.abs(values[i] - previousSpectrum[i]);
        }
        changed = previousSpectrum !== null && (spectrumDifference / values.length >= 1.5 || sound !== previousSound);
        audioPresence.textContent = "麦克风：有；声音：" + (sound ? "检测到" : "未检测到") + "；电平：" + level + "%";
        audioDigest.textContent = "音频摘要：" + hashValues(values);
        audioChange.textContent = "相对上一采样：" + (previousSpectrum === null ? "建立基线" : changed ? "有变化" : "基本稳定");
        audioChange.className = changed ? "changed" : "stable";
        audioLevel.style.width = level + "%";
        previousSpectrum = values;
        previousSound = sound;
    }

    function sampleMedia() {
        if (activeScreen !== "media") return;
        sampleImage();
        sampleAudio();
    }

    function showScreen(name) {
        if (name !== "weather" && name !== "media") name = "clock";
        activeScreen = name;
        clockScreen.hidden = name !== "clock";
        weatherScreen.hidden = name !== "weather";
        mediaScreen.hidden = name !== "media";
        if (name === "media") startMedia(); else stopMedia();
    }

    function applyAction(action, state) {
        var screen = state && state.screen;
        if (!screen) {
            if (action === "left") screen = "weather";
            else if (action === "right") screen = "media";
            else if (action === "clock") screen = "clock";
        }
        if (screen) showScreen(screen);
        document.body.className = state && (state.noisePlaying || state.noisePreparing) ? "noise-on" : "";
    }

    window.InfoPanelRemote = { applyAction: applyAction };
    window.addEventListener("beforeunload", stopMedia);
    updateClock();
    setInterval(updateClock, 250);
    if (window.InfoPanel && window.InfoPanel.getState) {
        try { applyAction("state", JSON.parse(window.InfoPanel.getState())); } catch (ignoredState) { }
    }
})();
