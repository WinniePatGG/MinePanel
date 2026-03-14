(function () {
    const STORAGE_KEY = 'minepanel.customTheme';

    function applyTheme(theme) {
        if (!theme || typeof theme !== 'object') {
            return;
        }

        const root = document.documentElement;
        Object.entries(theme).forEach(([name, value]) => {
            if (!name.startsWith('--')) {
                return;
            }
            if (typeof value !== 'string' || value.trim() === '') {
                return;
            }
            root.style.setProperty(name, value);
        });
    }

    function loadTheme() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            if (!raw) {
                return;
            }
            const parsed = JSON.parse(raw);
            applyTheme(parsed);
        } catch (ignored) {
            // Ignore malformed localStorage data.
        }
    }

    window.MinePanelTheme = {
        storageKey: STORAGE_KEY,
        applyTheme,
        loadTheme
    };

    loadTheme();
})();

