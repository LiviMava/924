package chatbot.chat.rag.tutorial;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import chatbot.chat.rag.ingest.DocumentParser;
import chatbot.chat.rag.model.*;
import chatbot.chat.rag.retrieval.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real adapters/HTTP against a local Qdrant contract stub; no cloud calls or real CAD writes. */
class TutorialServiceTest {
    @TempDir Path temp;
    final JsonMapper json=new JsonMapper();final Map<String,List<JsonNode>> collections=new ConcurrentHashMap<>();
    HttpServer server;ModelGateway models;TutorialService service;RagProperties properties;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            try {
                String path=e.getRequestURI().getPath(),method=e.getRequestMethod(),name=path.split("/")[2];
                String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);var body=json.readTree(raw.isEmpty()?"{}":raw);
                Object response;int status=200;
                if(method.equals("GET") && path.matches("/collections/[^/]+")) {
                    if(!collections.containsKey(name)) { status=404;response=Map.of("status","not found"); }
                    else response=Map.of("result",Map.of("points_count",collections.get(name).size(),"config",Map.of("params",Map.of("vectors",Map.of("size",3,"distance","Cosine")))));
                } else if(method.equals("PUT") && path.matches("/collections/[^/]+")) { collections.put(name,new ArrayList<>());response=Map.of("result",true); }
                else if(method.equals("PUT") && path.endsWith("/points")) { List<JsonNode> points=new ArrayList<>();body.path("points").forEach(points::add);collections.put(name,points);response=Map.of("result",true); }
                else if(method.equals("POST") && path.endsWith("/points/query")) {
                    float[] q=vector(body.path("query"));List<Map<String,Object>> hits=new ArrayList<>();
                    for(var point:collections.get(name)) { double score=TutorialService.cosine(q,vector(point.path("vector")));if(score>=body.path("score_threshold").asDouble())hits.add(Map.of("id",point.path("id").asText(),"score",score)); }
                    hits.sort(Comparator.<Map<String,Object>>comparingDouble(h->((Number)h.get("score")).doubleValue()).reversed());
                    response=Map.of("result",Map.of("points",hits.stream().limit(body.path("limit").asInt()).toList()));
                } else if(method.equals("DELETE")) { collections.remove(name);response=Map.of("result",true); }
                else { status=404;response=Map.of("status","unexpected path"); }
                byte[] bytes=json.writeValueAsBytes(response);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);
            } finally { e.close(); }
        });server.start();models=mock(ModelGateway.class);
        when(models.embeddingConfigured()).thenReturn(true);when(models.chatConfigured()).thenReturn(true);
        when(models.embed(anyList())).thenAnswer(inv->{List<String> texts=inv.getArgument(0);return texts.stream().map(t->t.contains("650")||t.contains("酒店每晚") ? new float[]{1,0,0} : new float[]{0,1,0}).toList();});
        properties=new RagProperties(temp,"",new RagProperties.Model("http://test.invalid","test","test-chat"),new RagProperties.Embedding("http://test.invalid","test","test-embedding",3),
            new RagProperties.Qdrant("127.0.0.1",6334,"http://127.0.0.1:"+server.getAddress().getPort(),"",false,"company_chunks_v1"),new RagProperties.Retrieval(30,6,0,"","",""));
        service=new TutorialService(properties,models,new DocumentParser(),new PromptAssembler(json),new Reranker(properties,json),json);
    }
    @AfterEach void stop() { server.stop(0); }
    private float[] vector(JsonNode n) { float[] v=new float[n.size()];for(int i=0;i<v.length;i++)v[i]=(float)n.get(i).asDouble();return v; }
    private String run() throws Exception { return json.valueToTree(service.create("owner","md")).path("runId").asText(); }
    @Test void actualPdfAndWordFixturesPreservePagesHeadingsAndTableValues() throws Exception {
        var parser=new DocumentParser();var pdf=parser.parse(service.sample("pdf"),"sample.pdf");
        assertEquals(List.of(1,2),pdf.stream().map(s->s.page()).toList());assertTrue(pdf.get(0).text().contains("650"));
        var word=parser.parse(service.sample("docx"),"sample.docx");assertTrue(word.stream().allMatch(s->s.page()==null));
        assertTrue(word.stream().anyMatch(s->s.text().contains("上海\t650")));assertTrue(word.size()>3);
    }
    @Test void keywordRecallWorksOfflineAndDualRecallKeepsBranchesIndependent() throws Exception {
        String id=run();service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));
        var keyword=json.valueToTree(service.retrieve("owner",id,new TutorialController.RetrieveRequest("上海酒店",5,0,"keyword"))).path("output");
        assertFalse(keyword.path("keywordHits").isEmpty());assertTrue(keyword.path("vectorHits").isEmpty());
        keyword.path("keywordHits").forEach(h->assertTrue(h.path("score").asDouble()>0));
        verify(models,never()).embed(anyList());
        var absent=json.valueToTree(service.retrieve("owner",id,new TutorialController.RetrieveRequest("zzzxnomatchword",5,0,"keyword"))).path("output");
        assertTrue(absent.path("keywordHits").isEmpty(),"No zero-score padding");
        service.embed("owner",id);
        when(models.embed(List.of("上海酒店"))).thenReturn(List.of(new float[]{1,1,1}));
        var dual=json.valueToTree(service.retrieve("owner",id,new TutorialController.RetrieveRequest("上海酒店",5,.99,"hybrid"))).path("output");
        assertFalse(dual.path("keywordHits").isEmpty());assertTrue(dual.path("vectorHits").isEmpty());
        assertEquals(3,dual.path("queryVector").path("dimensions").asInt());
    }
    @Test void stagesRequirePrerequisitesAndAreBoundToTheirSession() throws Exception {
        String id=run();assertEquals(409,assertThrows(ResponseStatusException.class,()->service.embed("owner",id)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.parse("other",id)).getStatusCode().value());
        service.parse("owner",id);assertThrows(IllegalArgumentException.class,()->service.chunk("owner",id,new TutorialController.ChunkRequest(220,220)));
        assertTrue(json.valueToTree(service.state("owner",id)).path("stages").has("parse"));
    }
    @Test void handbookPageExperimentPreservesPhysicalPagesAndSnapshotsSource() throws Exception {
        Path original=service.sample("pdf");
        var created=json.valueToTree(service.fromDocument("owner",original,"handbook.pdf",2,2));
        String id=created.path("runId").asText();
        assertEquals(2,created.path("fromPage").asInt());
        java.nio.file.Files.delete(original);
        var parsed=json.valueToTree(service.parse("owner",id)).path("output");
        assertEquals(1,parsed.path("sectionCount").asInt());
        assertEquals(2,parsed.path("sections").get(0).path("page").asInt());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.parse("other",id)).getStatusCode().value());
        service.delete("owner",id);
        assertThrows(IllegalArgumentException.class,()->service.fromDocument("owner",original,"handbook.pdf",1,4));
    }
    @Test void completePipelineStoresRealHttpPayloadAndInvalidatesDependentResults() throws Exception {
        String id=run();service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));
        var embedded=json.valueToTree(service.embed("owner",id));String collection=embedded.path("output").path("database").path("collection").asText();
        assertTrue(collection.startsWith("rag_tutorial_"));assertTrue(embedded.path("output").path("database").path("pointsCount").asInt()>1);
        String query="酒店每晚能报多少？";var retrieved=service.retrieve("owner",id,new TutorialController.RetrieveRequest(query,5,0));assertTrue(json.valueToTree(retrieved).path("output").path("hits").size()>0);
        service.fusion("owner",id,new TutorialController.FusionRequest(query,10));
        service.rerank("owner",id,new TutorialController.RerankRequest(query,"bm25","fused"));service.prompt("owner",id,new TutorialController.PromptRequest(query,3,"ranked"));
        when(models.tutorialAnswer(any())).thenReturn("住宿最高650元。[1]");var answer=json.valueToTree(service.generate("owner",id));assertTrue(answer.path("output").path("citationNumbersValid").asBoolean());assertFalse(answer.path("output").path("factsVerified").asBoolean());
        service.chunk("owner",id,new TutorialController.ChunkRequest(800,120));assertFalse(collections.containsKey(collection));
        assertFalse(json.valueToTree(service.state("owner",id)).path("stages").has("generate"));assertThrows(ResponseStatusException.class,()->service.generate("owner",id));
        assertFalse(json.valueToTree(service.state("owner",id)).path("stages").has("fusion"));
    }
    @Test void recallingAgainInvalidatesFusionAndRerankingAndCannotReuseAFormerQuestion() throws Exception {
        String id=run();service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));
        assertThrows(ResponseStatusException.class,()->service.fusion("owner",id,new TutorialController.FusionRequest("上海",10)));
        service.retrieve("owner",id,new TutorialController.RetrieveRequest("上海",5,0,"keyword"));
        assertThrows(ResponseStatusException.class,()->service.rerank("owner",id,new TutorialController.RerankRequest("上海","bm25","fused")));
        service.fusion("owner",id,new TutorialController.FusionRequest("上海",10));
        service.rerank("owner",id,new TutorialController.RerankRequest("上海","bm25","fused"));
        service.prompt("owner",id,new TutorialController.PromptRequest("上海",3,"ranked"));
        service.retrieve("owner",id,new TutorialController.RetrieveRequest("北京",5,0,"keyword"));
        var stages=json.valueToTree(service.state("owner",id)).path("stages");
        assertFalse(stages.has("fusion"));assertFalse(stages.has("rerank"));assertFalse(stages.has("prompt"));
        assertThrows(ResponseStatusException.class,()->service.fusion("owner",id,new TutorialController.FusionRequest("上海",10)));
        assertThrows(ResponseStatusException.class,()->service.prompt("owner",id,new TutorialController.PromptRequest("上海",3,"fused")));
        service.fusion("owner",id,new TutorialController.FusionRequest("北京",10));
    }
    @Test void configuredRerankDisplaysProviderScoreSeparatelyFromInputFusionScore() throws Exception {
        var ranker=mock(Reranker.class);when(ranker.configured()).thenReturn(true);when(ranker.mode()).thenReturn("http");
        when(ranker.model()).thenReturn("rerank");when(ranker.scoreType()).thenReturn("RAW_RELEVANCE");
        when(ranker.rerank(anyString(),anyList(),anyInt())).thenAnswer(inv->{
            List<SearchHit> hits=inv.getArgument(1);return List.of(hits.get(hits.size()-1).withScore(9.123456));
        });
        var configured=new TutorialService(properties,models,new DocumentParser(),new PromptAssembler(json),ranker,json);
        String id=json.valueToTree(configured.create("owner","md")).path("runId").asText();
        configured.parse("owner",id);configured.chunk("owner",id,new TutorialController.ChunkRequest(220,40));
        configured.retrieve("owner",id,new TutorialController.RetrieveRequest("上海",5,0,"keyword"));
        var fused=json.valueToTree(configured.fusion("owner",id,new TutorialController.FusionRequest("上海",10))).path("output").path("hits");
        var ranked=json.valueToTree(configured.rerank("owner",id,new TutorialController.RerankRequest("上海","configured","fused"))).path("output");
        assertEquals("RRF",ranked.path("inputScoreType").asText());assertEquals("RAW_RELEVANCE",ranked.path("scoreType").asText());
        assertEquals(fused.get(fused.size()-1).path("score").asDouble(),ranked.path("comparison").get(0).path("beforeScore").asDouble());
        assertEquals(9.123456,ranked.path("comparison").get(0).path("rerankScore").asDouble());
        assertEquals("rerank",ranked.path("model").asText());configured.delete("owner",id);
    }
    @Test void embeddingMismatchNeverUpsertsAndCleanupCannotTouchProductionCollection() throws Exception {
        String id=run();service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));
        when(models.embed(anyList())).thenAnswer(inv->{List<?> texts=inv.getArgument(0);return texts.stream().map(t->new float[]{1,0}).toList();});
        assertThrows(IllegalStateException.class,()->service.embed("owner",id));assertTrue(collections.values().stream().allMatch(List::isEmpty));
        service.delete("owner",id);assertTrue(collections.isEmpty());
        assertThrows(IllegalStateException.class,()->new QdrantIndex(properties,models,json).deleteTutorialCollection());
    }
    @Test void llmRerankAndPromptCanRunIndependentlyWithoutEmbeddingAndRejectBrokenOrders() throws Exception {
        String id=run();when(models.tutorialAnswer(any())).thenReturn("{\"order\":[2,3,1,0]}");
        var ranked=json.valueToTree(service.rerank("owner",id,new TutorialController.RerankRequest("上海酒店住宿限额","llm","fixture")));
        assertEquals("fixture-2",ranked.path("output").path("hits").get(0).path("id").asText());
        assertTrue(ranked.path("output").path("comparison").get(0).path("rerankScore").isNull());verify(models,never()).embed(anyList());
        var prompt=json.valueToTree(service.prompt("owner",id,new TutorialController.PromptRequest("上海酒店住宿限额",2,"ranked")));
        assertTrue(prompt.path("output").path("messages").get(1).path("content").asText().contains("650"));
        when(models.tutorialAnswer(any())).thenReturn("{\"order\":[2,2,1,0]}");assertThrows(IllegalStateException.class,()->service.rerank("owner",id,new TutorialController.RerankRequest("上海酒店住宿限额","llm","fixture")));
        var hits=List.of(new SearchHit("1","d","f",null,"s","x",0));
        assertThrows(IllegalStateException.class,()->TutorialRanking.parseOrder("{\"order\":[1]}",hits,json));
        assertThrows(IllegalStateException.class,()->TutorialRanking.parseOrder("{\"order\":[0.5]}",hits,json));
    }
    @Test void changingQuestionCannotReuseCandidatesFromAFormerRecall() throws Exception {
        String id=run();service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));service.embed("owner",id);
        service.retrieve("owner",id,new TutorialController.RetrieveRequest("酒店每晚",5,0));
        service.rerank("owner",id,new TutorialController.RerankRequest("北京费用","bm25","fixture"));
        assertThrows(ResponseStatusException.class,()->service.prompt("owner",id,new TutorialController.PromptRequest("北京费用",2,"retrieved")));
    }
    @Test void twoExperimentsHaveDifferentCollectionsAndDeletingOneKeepsTheOther() throws Exception {
        String one=run(),two=run();for(String id:List.of(one,two)) {service.parse("owner",id);service.chunk("owner",id,new TutorialController.ChunkRequest(220,40));service.embed("owner",id);}
        assertEquals(2,collections.size());service.delete("owner",one);assertEquals(1,collections.size());
        assertThrows(ResponseStatusException.class,()->service.state("owner",one));assertNotNull(service.state("owner",two));
    }
}
