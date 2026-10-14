package chatbot.chat.rag.service;

import chatbot.chat.rag.api.ApiModels.*;
import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.model.ModelGateway;
import chatbot.chat.rag.retrieval.HybridRetriever;
import chatbot.chat.rag.store.DocumentRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class RagService {
    public record Prepared(HybridRetriever.Result retrieval, Prompt prompt) {}
    public static final String NO_EVIDENCE = "当前知识库没有找到足够的相关资料，无法依据公司文档回答。请补充文档，或提供更具体的关键词。";
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private final HybridRetriever retriever;
    private final ModelGateway model;
    private final DocumentRepository repository;
    private final RagProperties properties;
    private final chatbot.chat.rag.model.PromptAssembler prompts;
    public RagService(HybridRetriever retriever, ModelGateway model, DocumentRepository repository,
                      RagProperties properties, ObjectMapper json) {
        this.retriever=retriever;this.model=model;this.repository=repository;this.properties=properties;
        this.prompts=new chatbot.chat.rag.model.PromptAssembler(json);
    }
    public Prepared prepare(ChatRequest request) {
        var history=request.history()==null ? List.<HistoryMessage>of() : request.history();
        int total=history.stream().mapToInt(m->m.content().length()).sum();
        if (total>24000) throw new IllegalArgumentException("历史对话总长度不能超过24000字符");
        String query=request.question();
        // Include the last user turn only for an explicitly referential follow-up.
        if (query.matches("(?s).*(它|这个|那个|这些|上述|刚才|那么|那报|那需要|那可以|what about|that policy).*")) {
            for (int i=history.size()-1;i>=0;i--) if (history.get(i).role().equals("user")) {
                String previous=history.get(i).content();
                query=previous.substring(0,Math.min(1000,previous.length()))+"\n"+query; break;
            }
        }
        var result=retriever.search(query,properties.retrieval().topK(),request.documentId());
        if (result.hits().isEmpty()) return new Prepared(result,null);
        if (!model.chatConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "聊天模型尚未配置。请在 .local.env 设置 RAG_CHAT_BASE_URL、RAG_CHAT_MODEL、RAG_CHAT_API_KEY；当前可使用检索功能。");
        return new Prepared(result,prompts.assemble(request.question(),history,result.hits()));
    }
    public ChatResponse answer(ChatRequest request) {
        Prepared prepared=prepare(request);
        return finish(prepared,prepared.prompt()==null ? NO_EVIDENCE : model.answer(prepared.prompt()));
    }
    public ChatResponse finish(Prepared prepared, String answer) {
        var result=prepared.retrieval();
        List<String> warnings=new ArrayList<>(result.warnings());
        if (result.hits().isEmpty()) return new ChatResponse(NO_EVIDENCE,List.of(),false,result.retrievalMode(),warnings);
        var current=repository.readyHits(result.hits().stream().map(h->h.id()).toList());
        if (current.size()!=result.hits().size())
            return new ChatResponse("回答期间来源文档已删除或重新索引，请重新提问。",List.of(),false,result.retrievalMode(),warnings);
        var matcher=CITATION.matcher(Objects.requireNonNullElse(answer,""));
        boolean found=false;
        while (matcher.find()) {
            found=true;
            int number;
            try { number=Integer.parseInt(matcher.group(1)); } catch(NumberFormatException e) { number=0; }
            if(number<1 || number>result.hits().size())
                return new ChatResponse("模型返回的引用无法对应资料，请查看来源片段或重新提问。",result.hits(),false,result.retrievalMode(),
                    append(warnings,"引用编号校验未通过，已拦截该答案"));
        }
        if (answer==null || answer.isBlank()) throw new IllegalStateException("模型未返回有效答案");
        if (!found) warnings.add("模型未提供来源编号，请核对检索到的原文；引用校验不等于事实正确性评测");
        return new ChatResponse(answer,result.hits(),found,result.retrievalMode(),List.copyOf(warnings));
    }
    private List<String> append(List<String> warnings,String warning) {
        warnings.add(warning);return List.copyOf(warnings);
    }
}
