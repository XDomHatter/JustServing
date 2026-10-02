package tech.xdomhatter.core.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {}
}
