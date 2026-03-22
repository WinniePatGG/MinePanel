(function () {
    const STORAGE_KEY = 'minepanel.customTheme';
    const CURRENT_USER_CACHE_KEY = 'minepanel.me.cache';
    const CURRENT_USER_CACHE_TTL_MS = 5000;
    const EXT_NAV_CACHE_KEY = 'minepanel.extNav.cache';
    const EXT_NAV_CACHE_TTL_MS = 30000;

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

        const extensionManagedPaths = new Set([
            '/dashboard/world-backups',
            '/dashboard/maintenance',
            '/dashboard/whitelist',
            '/dashboard/announcements',
            '/dashboard/reports',
            '/dashboard/tickets'
        ]);

        ensurePanelExtensionsLink(sideNav);
        ensurePanelAccountLink(sideNav);
        ensurePanelExtensionConfigLink(sideNav);
        enforceServerCategoryOrder(sideNav);

        const cachedTabs = readCachedExtensionTabs();
        if (cachedTabs.length > 0) {
            applyExtensionTabs(sideNav, cachedTabs, extensionManagedPaths);
            ensurePanelAccountLink(sideNav);
            enforceServerCategoryOrder(sideNav);
            await applySidebarPermissionVisibility(sideNav);
        }

        try {
            const response = await fetch('/api/extensions/navigation', { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) {
                return;
            }

            const payload = await response.json();
            const tabs = Array.isArray(payload.tabs) ? payload.tabs : [];
            applyExtensionTabs(sideNav, tabs, extensionManagedPaths);
            writeCachedExtensionTabs(tabs);

            if (tabs.length === 0) {
                ensurePanelAccountLink(sideNav);
                enforceServerCategoryOrder(sideNav);
                await applySidebarPermissionVisibility(sideNav);
                return;
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

    function applyExtensionTabs(sideNav, tabs, extensionManagedPaths) {
        const sanitizedTabs = (Array.isArray(tabs) ? tabs : [])
            .filter(tab => tab && typeof tab.path === 'string' && typeof tab.label === 'string' && typeof tab.category === 'string')
            .map(tab => ({
                path: tab.path.trim(),
                label: tab.label.trim(),
                category: tab.category.trim().toLowerCase()
            }))
            .filter(tab => tab.path && tab.category);

        const runtimeTabPaths = new Set(sanitizedTabs.map(tab => tab.path));

        // Remove extension links that are not part of the currently loaded extension set.
        sideNav.querySelectorAll('a.side-link').forEach(link => {
            const href = (link.getAttribute('href') || '').trim();
            if (!extensionManagedPaths.has(href)) {
                return;
            }
            if (!runtimeTabPaths.has(href)) {
                link.remove();
            }
        });

        for (const tab of sanitizedTabs) {
            const container = ensureCategoryContainer(sideNav, tab.category);
            if (!container) {
                continue;
            }

            let link = sideNav.querySelector(`a.side-link[href="${cssEscape(tab.path)}"]`);
            if (!link) {
                link = document.createElement('a');
                link.className = 'side-link';
                link.href = tab.path;
                container.appendChild(link);
            } else if (link.parentElement !== container) {
                container.appendChild(link);
            }

            link.textContent = tab.label || tab.path;
            link.classList.toggle('active', window.location.pathname === tab.path);
        }
    }

    function readCachedExtensionTabs() {
        const now = Date.now();
        try {
            const cachedRaw = sessionStorage.getItem(EXT_NAV_CACHE_KEY);
            if (!cachedRaw) {
                return [];
            }

            const cached = JSON.parse(cachedRaw);
            if (!cached || typeof cached !== 'object' || Number(cached.expiresAt || 0) <= now) {
                return [];
            }

            return Array.isArray(cached.tabs) ? cached.tabs : [];
        } catch (ignored) {
            return [];
        }
    }

    function writeCachedExtensionTabs(tabs) {
        try {
            sessionStorage.setItem(EXT_NAV_CACHE_KEY, JSON.stringify({
                tabs: Array.isArray(tabs) ? tabs : [],
                expiresAt: Date.now() + EXT_NAV_CACHE_TTL_MS
            }));
        } catch (ignored) {
            // Ignore unavailable session storage.
        }
    }

    function cssEscape(value) {
        if (typeof window.CSS !== 'undefined' && typeof window.CSS.escape === 'function') {
            return window.CSS.escape(value);
        }
        return String(value).replace(/"/g, '\\"');
    }

    async function applySidebarPermissionVisibility(sideNav) {
        const me = await fetchCurrentUser();
        if (!me) {
            return;
        }

        if (!Array.isArray(me.permissions)) {
            return;
        }

        if (me.isOwner === true) {
            sideNav.querySelectorAll('a.side-link').forEach(link => {
                link.style.display = '';
            });
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
            ['/dashboard/announcements', 'VIEW_ANNOUNCEMENTS'],
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

    async function fetchCurrentUser() {
        const now = Date.now();
        try {
            const cachedRaw = sessionStorage.getItem(CURRENT_USER_CACHE_KEY);
            if (cachedRaw) {
                const cached = JSON.parse(cachedRaw);
                if (cached && typeof cached === 'object' && Number(cached.expiresAt || 0) > now) {
                    return cached.user || null;
                }
            }
        } catch (ignored) {
            // Ignore malformed cache state.
        }

        try {
            const response = await fetch('/api/me', { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) {
                return null;
            }

            const payload = await response.json();
            const user = payload && payload.user ? payload.user : null;

            try {
                sessionStorage.setItem(CURRENT_USER_CACHE_KEY, JSON.stringify({
                    user,
                    expiresAt: now + CURRENT_USER_CACHE_TTL_MS
                }));
            } catch (ignored) {
                // Ignore unavailable sessionStorage.
            }
            return user;
        } catch (ignored) {
            return null;
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
            '/dashboard/maintenance',
            '/dashboard/whitelist',
            '/dashboard/announcements',
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
        window.setInterval(() => {
            if (document.hidden) {
                return;
            }
            checkVersion();
        }, 5000);
    }

    loadTheme();
    bootstrapExtensionNavigationTabs();
    startLiveAssetRefresh();
})();

