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
        ensurePanelAccountLink(sideNav);
        ensurePanelExtensionConfigLink(sideNav);
        enforceServerCategoryOrder(sideNav);

        try {
            const response = await fetch('/api/extensions/navigation', { credentials: 'same-origin' });
            if (!response.ok) {
                return;
            }

            const payload = await response.json();
            const tabs = Array.isArray(payload.tabs) ? payload.tabs : [];
            if (tabs.length === 0) {
                ensurePanelAccountLink(sideNav);
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

            ensurePanelAccountLink(sideNav);
            enforceServerCategoryOrder(sideNav);
            await applySidebarPermissionVisibility(sideNav);
        } catch (ignored) {
            // Ignore extension tab loading issues on login/setup pages.
            ensurePanelAccountLink(sideNav);
            enforceServerCategoryOrder(sideNav);
            await applySidebarPermissionVisibility(sideNav);
        }
    }

    async function applySidebarPermissionVisibility(sideNav) {
        let me;
        try {
            const response = await fetch('/api/me', { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) {
                return;
            }
            const payload = await response.json();
            me = payload && payload.user ? payload.user : null;
        } catch (ignored) {
            return;
        }

        if (!me || !Array.isArray(me.permissions)) {
            return;
        }

        const permissionSet = new Set(me.permissions);
        const linkPermissions = new Map([
            ['/dashboard/overview', 'VIEW_OVERVIEW'],
            ['/console', 'VIEW_CONSOLE'],
            ['/dashboard/console', 'VIEW_CONSOLE'],
            ['/dashboard/resources', 'VIEW_RESOURCES'],
            ['/dashboard/players', 'VIEW_PLAYERS'],
            ['/dashboard/bans', 'VIEW_BANS'],
            ['/dashboard/plugins', 'VIEW_PLUGINS'],
            ['/dashboard/users', 'VIEW_USERS'],
            ['/dashboard/discord-webhook', 'VIEW_DISCORD_WEBHOOK'],
            ['/dashboard/themes', 'VIEW_THEMES'],
            ['/dashboard/extensions', 'VIEW_EXTENSIONS'],
            ['/dashboard/extension-config', 'VIEW_EXTENSIONS'],
            ['/dashboard/account', 'ACCESS_PANEL'],
            ['/dashboard/world-backups', 'VIEW_BACKUPS'],
            ['/dashboard/maintenance', 'VIEW_MAINTENANCE'],
            ['/dashboard/whitelist', 'VIEW_WHITELIST'],
            ['/dashboard/reports', 'VIEW_REPORTS'],
            ['/dashboard/tickets', 'VIEW_TICKETS']
        ]);

        sideNav.querySelectorAll('a.side-link').forEach(link => {
            const href = link.getAttribute('href') || '';
            const required = linkPermissions.get(href);
            if (!required) {
                return;
            }

            const allowed = permissionSet.has(required);
            link.style.display = allowed ? '' : 'none';
        });
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
            '/dashboard/maintenance',
            '/dashboard/whitelist',
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

    function ensurePanelAccountLink(sideNav) {
        if (!sideNav) {
            return;
        }

        sideNav.querySelectorAll('a.side-link[href="/dashboard/account"]').forEach(link => {
            if (link.dataset.accountBottomLink === 'true') {
                return;
            }
            link.remove();
        });

        let link = sideNav.querySelector('a.side-link[data-account-bottom-link="true"]');
        if (!link) {
            link = document.createElement('a');
            link.className = 'side-link';
            link.dataset.accountBottomLink = 'true';
            link.href = '/dashboard/account';
            link.textContent = 'Account';
        }

        link.classList.remove('active');
        if (window.location.pathname === '/dashboard/account') {
            link.classList.add('active');
        }

        // Keep Account as the bottom nav item below panel links/categories.
        sideNav.appendChild(link);
    }

    function ensurePanelExtensionConfigLink(sideNav) {
        const panelContainer = ensureCategoryContainer(sideNav, 'panel');
        if (!panelContainer) {
            return;
        }

        const existing = panelContainer.querySelector('a.side-link[href="/dashboard/extension-config"]');
        if (existing) {
            if (window.location.pathname === '/dashboard/extension-config') {
                existing.classList.add('active');
            }
            return;
        }

        const link = document.createElement('a');
        link.className = 'side-link';
        if (window.location.pathname === '/dashboard/extension-config') {
            link.classList.add('active');
        }
        link.href = '/dashboard/extension-config';
        link.textContent = 'Extension Config';
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

