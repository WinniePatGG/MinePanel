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

    async function loadExtensionNavigationTabs() {
        const sideNav = document.querySelector('.side-nav');
        if (!sideNav) {
            return;
        }

        ensurePanelExtensionsLink(sideNav);
        enforceServerCategoryOrder(sideNav);

        try {
            const response = await fetch('/api/extensions/navigation', { credentials: 'same-origin' });
            if (!response.ok) {
                return;
            }

            const payload = await response.json();
            const tabs = Array.isArray(payload.tabs) ? payload.tabs : [];
            if (tabs.length === 0) {
                enforceServerCategoryOrder(sideNav);
                return;
            }

            const existingHrefs = new Set(Array.from(sideNav.querySelectorAll('a.side-link')).map(link => link.getAttribute('href')));
            for (const tab of tabs) {
                if (!tab || typeof tab.path !== 'string' || typeof tab.label !== 'string' || typeof tab.category !== 'string') {
                    continue;
                }

                const href = tab.path.trim();
                if (!href || existingHrefs.has(href)) {
                    continue;
                }

                const category = tab.category.trim().toLowerCase();
                const container = ensureCategoryContainer(sideNav, category);
                if (!container) {
                    continue;
                }

                const link = document.createElement('a');
                link.className = 'side-link';
                if (window.location.pathname === href) {
                    link.classList.add('active');
                }
                link.href = href;
                link.textContent = tab.label.trim() || href;
                container.appendChild(link);
                existingHrefs.add(href);
            }

            enforceServerCategoryOrder(sideNav);
        } catch (ignored) {
            // Ignore extension tab loading issues on login/setup pages.
            enforceServerCategoryOrder(sideNav);
        }
    }

    function enforceServerCategoryOrder(sideNav) {
        const serverContainer = sideNav.querySelector('.side-category-items[data-category-items="server"]');
        if (!serverContainer) {
            return;
        }

        const preferredOrder = [
            '/console',
            '/dashboard/console',
            '/dashboard/resources',
            '/dashboard/world-backups',
            '/dashboard/players',
            '/dashboard/bans',
            '/dashboard/plugins',
            '/dashboard/reports',
            '/dashboard/tickets'
        ];

        const links = Array.from(serverContainer.querySelectorAll('a.side-link'));
        if (links.length <= 1) {
            return;
        }

        links.sort((left, right) => {
            const leftHref = left.getAttribute('href') || '';
            const rightHref = right.getAttribute('href') || '';
            const leftIndex = preferredOrder.indexOf(leftHref);
            const rightIndex = preferredOrder.indexOf(rightHref);

            if (leftIndex >= 0 && rightIndex >= 0) {
                return leftIndex - rightIndex;
            }
            if (leftIndex >= 0) {
                return -1;
            }
            if (rightIndex >= 0) {
                return 1;
            }
            return leftHref.localeCompare(rightHref);
        });

        for (const link of links) {
            serverContainer.appendChild(link);
        }
    }

    function ensureCategoryContainer(sideNav, category) {
        let container = sideNav.querySelector(`.side-category-items[data-category-items="${category}"]`);
        if (container) {
            return container;
        }

        // Allow extensions to define additional categories without touching every page template.
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'side-category-toggle';
        toggle.dataset.category = category;
        toggle.textContent = category.charAt(0).toUpperCase() + category.slice(1);

        container = document.createElement('div');
        container.className = 'side-category-items';
        container.dataset.categoryItems = category;

        sideNav.appendChild(toggle);
        sideNav.appendChild(container);

        // Keep behavior consistent with per-page sidebar category script.
        toggle.addEventListener('click', () => {
            const expanded = !container.classList.contains('expanded');
            container.classList.toggle('expanded', expanded);
            toggle.classList.toggle('expanded', expanded);
        });

        return container;
    }

    function ensurePanelExtensionsLink(sideNav) {
        const panelContainer = ensureCategoryContainer(sideNav, 'panel');
        if (!panelContainer) {
            return;
        }

        const existing = panelContainer.querySelector('a.side-link[href="/dashboard/extensions"]');
        if (existing) {
            if (window.location.pathname === '/dashboard/extensions') {
                existing.classList.add('active');
            }
            return;
        }

        const link = document.createElement('a');
        link.className = 'side-link';
        if (window.location.pathname === '/dashboard/extensions') {
            link.classList.add('active');
        }
        link.href = '/dashboard/extensions';
        link.textContent = 'Extensions';
        panelContainer.appendChild(link);
    }

    function bootstrapExtensionNavigationTabs() {
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => {
                loadExtensionNavigationTabs();
            });
            return;
        }
        loadExtensionNavigationTabs();
    }

    function startLiveAssetRefresh() {
        let lastVersion = null;

        async function checkVersion() {
            try {
                const response = await fetch('/api/web/live-version', {
                    credentials: 'same-origin',
                    cache: 'no-store'
                });
                if (!response.ok) {
                    return;
                }

                const payload = await response.json();
                const nextVersion = Number(payload.version || 0);
                if (!Number.isFinite(nextVersion) || nextVersion <= 0) {
                    return;
                }

                if (lastVersion === null) {
                    lastVersion = nextVersion;
                    return;
                }

                if (nextVersion > lastVersion) {
                    window.location.reload();
                    return;
                }

                lastVersion = nextVersion;
            } catch (ignored) {
                // Ignore transient connectivity issues and try again on next interval.
            }
        }

        checkVersion();
        window.setInterval(checkVersion, 2000);
    }

    loadTheme();
    bootstrapExtensionNavigationTabs();
    startLiveAssetRefresh();
})();

