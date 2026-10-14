package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.domain.SearchHit;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Strict permutation validation shared by production and teaching LLM reranking. */
public final class ListwiseRanking {
    private ListwiseRanking() {}
    public static List<SearchHit> parseOrder(String answer,List<SearchHit> hits,ObjectMapper json) {
        return parseSelection(answer,hits,hits.size(),json);
    }
    public static List<SearchHit> parseSelection(String answer,List<SearchHit> hits,int count,ObjectMapper json) {
        if(answer==null || answer.length()>4000)throw new IllegalStateException("重排模型输出无效");
        String text=answer.strip();
        if(text.startsWith("```"))text=text.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
        var order=json.readTree(text).path("order");
        if(!order.isArray() || order.size()!=count)throw new IllegalStateException("重排模型返回数量与要求不符");
        Set<Integer> seen=new HashSet<>();List<SearchHit> result=new ArrayList<>();
        for(var item:order) {
            int index=item.isIntegralNumber() && item.canConvertToInt()?item.asInt():-1;
            if(index<0 || index>=hits.size() || !seen.add(index))throw new IllegalStateException("重排模型返回重复或越界序号");
            result.add(hits.get(index));
        }
        return List.copyOf(result);
    }
}
