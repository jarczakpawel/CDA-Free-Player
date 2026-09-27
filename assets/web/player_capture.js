(function () {
    if (window.__CDA_FP_CAPTURE_INSTALLED) return;
    window.__CDA_FP_CAPTURE_INSTALLED = true;
    var selector = '[player_data],[data-player-data],[data-player_data]';
    function emit(value, structured) {
        try {
            if (!value) return '';
            var raw = typeof value === 'string' ? value : JSON.stringify(value);
            var data;
            try {
                data = JSON.parse(raw);
            } catch (error) {
                var text = document.createElement('textarea');
                text.innerHTML = raw;
                raw = text.value;
                data = JSON.parse(raw);
            }
            var video = data && data.video;
            if (!video) return '';
            var ready = [video.manifest, video.manifest_apple, video.file].some(function (url) {
                return typeof url === 'string' && /^(https?:)?\/\//.test(url);
            }) ||
                (video.hash2 && video.qualities && (video.ts || (data.api && data.api.ts)));
            if (!ready) return '';
            if (structured) window.__CDA_FP_STRUCTURED = raw;
            if (raw !== window.__CDA_FP_CAPTURED) {
                window.__CDA_FP_CAPTURED = raw;
                if (window.CdaFreePlayerBridge) {
                    window.CdaFreePlayerBridge.postMessage(JSON.stringify({
                        job: window.__CDA_FP_JOB__, data: raw
                    }));
                }
            }
            return raw;
        } catch (error) {
            return '';
        }
    }
    function direct(url) {
        if (typeof url !== 'string') return '';
        var value = url.trim();
        if (value.indexOf('//') === 0) value = 'https:' + value;
        if (!/^https?:\/\//i.test(value)) return '';
        var clean = value.split('#', 1)[0].toLowerCase();
        var video = {type: 'plain'};
        if (/\.mpd(?:\?|$)/i.test(clean)) video.manifest = value;
        else if (/\.m3u8(?:\?|$)/i.test(clean)) video.manifest_apple = value;
        else if (/\.(?:mp4|m4v)(?:\?|$)/i.test(clean)) video.file = value;
        else return '';
        return emit({video: video}, false);
    }
    function one(node) {
        if (!node || node.nodeType !== 1) return '';
        return emit(node.getAttribute('player_data') || node.getAttribute('data-player-data') ||
            node.getAttribute('data-player_data'), true);
    }
    function scan(node) {
        var found = one(node);
        if (found) return found;
        var nodes = node.querySelectorAll ? node.querySelectorAll(selector) : [];
        for (var i = 0; i < nodes.length; i++) {
            found = one(nodes[i]);
            if (found) return found;
        }
        return '';
    }
    function scanMedia(doc) {
        try {
            var videos = doc.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
                var video = videos[i];
                var found = direct(video.currentSrc || video.src || video.getAttribute('src'));
                if (found) return found;
                var sources = video.querySelectorAll('source[src]');
                for (var j = 0; j < sources.length; j++) {
                    found = direct(sources[j].src || sources[j].getAttribute('src'));
                    if (found) return found;
                }
            }
        } catch (error) {}
        return '';
    }
    window.__CDA_FP_READ_STRUCTURED = function readStructured() {
        var found = window.__CDA_FP_STRUCTURED || scan(document) ||
            emit(window.player_data || window.playerData || window.__PLAYER_DATA__, true);
        if (found) return found;
        for (var i = 0; i < window.frames.length; i++) {
            try {
                var frame = window.frames[i];
                found = frame.__CDA_FP_READ_STRUCTURED ? frame.__CDA_FP_READ_STRUCTURED() :
                    scan(frame.document) || emit(frame.player_data || frame.playerData || frame.__PLAYER_DATA__, true);
                if (found) return found;
            } catch (error) {}
        }
        return '';
    };
    window.__CDA_FP_READ_PLAYER = function read() {
        var found = window.__CDA_FP_READ_STRUCTURED();
        if (found) return found;
        found = window.__CDA_FP_CAPTURED || scanMedia(document);
        if (found) return found;
        for (var i = 0; i < window.frames.length; i++) {
            try {
                var frame = window.frames[i];
                found = frame.__CDA_FP_READ_PLAYER ? frame.__CDA_FP_READ_PLAYER() : scanMedia(frame.document);
                if (found) return found;
            } catch (error) {}
        }
        return '';
    };
    new MutationObserver(function (changes) {
        for (var i = 0; i < changes.length; i++) {
            var change = changes[i];
            if (change.type === 'attributes' && change.attributeName !== 'src') {
                if (!one(change.target) && !window.__CDA_FP_STRUCTURED) emit(change.oldValue, true);
            }
            for (var j = 0; j < change.addedNodes.length; j++) scan(change.addedNodes[j]);
        }
        if (!window.__CDA_FP_CAPTURED) window.__CDA_FP_READ_PLAYER();
    }).observe(document, {
        subtree: true, childList: true, attributes: true, attributeOldValue: true,
        attributeFilter: ['player_data', 'data-player-data', 'data-player_data', 'src']
    });
    window.__CDA_FP_READ_PLAYER();
})();
