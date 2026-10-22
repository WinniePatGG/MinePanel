package de.winniepat.minePanel.extensions;

import de.winniepat.minePanel.users.PanelPermission;
import spark.Response;

import java.util.Map;

public interface ExtensionWebRegistry {

    void get(String path, PanelPermission permission, ExtensionRouteHandler handler);

    void post(String path, PanelPermission permission, ExtensionRouteHandler handler);

    String json(Response response, int status, Map<String, Object> payload);
}

