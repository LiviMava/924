package chatbot.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 一个聊天会话：按厂商惰性创建并持有 SimpleAgent。
 * 切换厂商再切回来，各厂商的对话历史都保留（Map<Provider, SimpleAgent>）。
 * 「新对话」由外层移除整个 ChatSession 实现。
 * 线程边界：本类只保证 agent 的创建与映射线程安全；返回的 SimpleAgent 实例
 * 非线程安全，同一厂商的并发 chat() 需上层保证互斥（前端 busy 位仅覆盖单标签页）。
 */
public class ChatSession {

    private final Function<Provider, SimpleAgent> agentFactory;
    private final Map<Provider, SimpleAgent> agents = new ConcurrentHashMap<>();

    public ChatSession(Function<Provider, SimpleAgent> agentFactory) {
        this.agentFactory = agentFactory;
    }

    /** 该厂商的 agent，不存在则用工厂创建（每厂商至多创建一次；工厂抛异常则不写入映射，下次调用重试） */
    public SimpleAgent agent(Provider provider) {
        return agents.computeIfAbsent(provider, agentFactory);
    }
}
