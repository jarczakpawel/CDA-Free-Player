(function () {
    if (window.__CDA_FP_CAPTURE_INSTALLED) return;
    window.__CDA_FP_CAPTURE_INSTALLED = true;
    var selector = '[player_data],[data-player-data],[data-player_data]';
    function emit(value) {
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
            var premium = [data.premium, video.premium].some(function (value) {
                return value === true || value === 1 || value === '1' || value === 'true';
            });
            if (!ready && !premium) return '';
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
    function one(node) {
        if (!node || node.nodeType !== 1) return '';
        return emit(node.getAttribute('player_data') || node.getAttribute('data-player-data') ||
            node.getAttribute('data-player_data'));
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
    window.__CDA_FP_READ_PLAYER = function read() {
        var found = window.__CDA_FP_CAPTURED || scan(document) ||
            emit(window.player_data || window.playerData || window.__PLAYER_DATA__);
        if (found) return found;
        for (var i = 0; i < window.frames.length; i++) {
            try {
                var frame = window.frames[i];
                found = frame.__CDA_FP_READ_PLAYER ? frame.__CDA_FP_READ_PLAYER() :
                    scan(frame.document) || emit(frame.player_data || frame.playerData || frame.__PLAYER_DATA__);
                if (found) return found;
            } catch (error) {}
        }
        return '';
    };
    new MutationObserver(function (changes) {
        for (var i = 0; i < changes.length; i++) {
            var change = changes[i];
            if (change.type === 'attributes') {
                if (!one(change.target) && !window.__CDA_FP_CAPTURED) emit(change.oldValue);
            }
            for (var j = 0; j < change.addedNodes.length; j++) scan(change.addedNodes[j]);
        }
    }).observe(document, {
        subtree: true, childList: true, attributes: true, attributeOldValue: true,
        attributeFilter: ['player_data', 'data-player-data', 'data-player_data']
    });
    window.__CDA_FP_READ_PLAYER();
})();
