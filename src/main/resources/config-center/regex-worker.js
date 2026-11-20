'use strict';
self.onmessage = event => {
    try {
        self.postMessage(new RegExp(event.data.pattern, 'u').test(event.data.value));
    }
    catch {
        self.postMessage(false);
    }
};
