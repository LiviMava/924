package chatbot.chat;

/**
 * 换厂商的唯一入口。四家都提供 OpenAI 兼容接口，只是 baseUrl / 模型名不同。
 * key 只从环境变量读（服务化后不再把 key 写进代码）。
 */
public enum Provider {
    //        baseUrl                                   模型名                 环境变量
    DEEPSEEK("https://api.deepseek.com",               "deepseek-flash",   "DEEPSEEK_API_KEY"),
    GLM(     "https://open.bigmodel.cn/api/paas/v4",   "glm-4.6",             "GLM_API_KEY"),
    KIMI(    "https://api.moonshot.cn/v1",             "kimi-k2-0905-preview","KIMI_API_KEY"),
    MINIMAX( "https://api.minimaxi.com/v1",            "MiniMax-M2",          "MINIMAX_API_KEY");

    public final String baseUrl;
    public final String model;
    public final String envVar;

    Provider(String baseUrl, String model, String envVar) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.envVar = envVar;
    }

    /** 读环境变量；缺失/空白返回 null（委托给被测的纯函数，生产行为与测试同源） */
    public String resolveApiKey() {
        return resolveApiKey(System.getenv(envVar), null);
    }

    /** 纯函数，便于测试：envValue 非空白则用之，否则用 fallback，两者皆缺则 null */
    static String resolveApiKey(String envValue, String fallback) {
        if (envValue != null && !envValue.isBlank()) return envValue;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return null;
    }
}
