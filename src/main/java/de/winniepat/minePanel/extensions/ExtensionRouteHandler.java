package de.winniepat.minePanel.extensions;

import de.winniepat.minePanel.users.PanelUser;
import spark.Request;
import spark.Response;

@FunctionalInterface
public interface ExtensionRouteHandler {
    Object handle(Request request, Response response, PanelUser user) throws Exception;
}

