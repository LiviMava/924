(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const themeKey='zhixu.rag.theme';
  const systemTheme=window.matchMedia('(prefers-color-scheme: dark)');
  let preferredTheme;
  try { preferredTheme=localStorage.getItem(themeKey); } catch (_) {}
  function applyTheme(theme) {
    const dark=theme==='dark';document.documentElement.dataset.theme=theme;
    $('theme-toggle').setAttribute('aria-pressed',String(dark));
    $('theme-toggle').title=dark?'当前为暗色，切换到亮色主题':'当前为亮色，切换到暗色主题';
    $('theme-label').textContent=dark?'亮色主题':'暗色主题';
  }
  const validTheme=theme=>theme==='dark'||theme==='light';
  applyTheme(validTheme(preferredTheme)?preferredTheme:systemTheme.matches?'dark':'light');
  $('theme-toggle').onclick=()=>{
    preferredTheme=document.documentElement.dataset.theme==='dark'?'light':'dark';applyTheme(preferredTheme);
    try { localStorage.setItem(themeKey,preferredTheme); } catch (_) {}
  };
  systemTheme.addEventListener('change',event=>{if(!validTheme(preferredTheme))applyTheme(event.matches?'dark':'light');});
  window.addEventListener('storage',event=>{
    if(event.key===themeKey) { preferredTheme=event.newValue;applyTheme(validTheme(preferredTheme)?preferredTheme:systemTheme.matches?'dark':'light'); }
  });
  const lessons = [
    ['parse','文档解析','把文件变成带来源位置的文字。','PDF、Word 是文件容器。检索需要其中的文字，还需要保留页码或章节，才能把答案追溯到原文。','试试 Word：表格会变成文字行；试试两页 PDF：观察 page 从 1 开始。'],
    ['chunk','分块','把长文档切成可检索、可引用的片段。','片段太长会混入无关信息，太短可能丢失上下文。重叠保留边界信息，段落和章节决定怎样切得更自然。','把 220/40 改成 800/120，比较片段数量。高亮标记表示与上一片段相同的前缀。'],
    ['embed','Embedding 入库','文字 → 数值向量 → 数据库中的点。','Embedding 模型将文本编码成高维向量。Qdrant 保存完整向量与 payload；维度必须一致，片段 ID 用于关联原文。','观察实际 2048 维向量的前 8 维与范数。单个维度没有可直接解释的“城市”含义。'],
    ['retrieve','向量召回','把问题也编码成向量，再找相近的片段。','文档向量预先生成；提问时只生成问题向量。Qdrant 按 Cosine 排序，并通过 Top-K 和阈值控制候选。','换一种问法，或者将阈值提高到 0.99。候选变少不代表答案更正确。'],
    ['rerank','重排','让候选进一步围绕当前问题排序。','召回追求找到候选，重排进一步判断候选能否回答问题。专用 Cross-Encoder 和 LLM 都可用于重排，但成本与输出合同不同。','选择独立候选集，对比上海办公室餐饮、北京住宿、上海住宿。LLM 是真实调用；BM25 是离线关键词对照。'],
    ['prompt','组装 Prompt','把规则、问题和带编号证据组织成模型输入。','Prompt 不只是把文字相加。系统规则与文档数据要区分；证据保留来源和编号，历史对话也不能替代事实依据。','改变证据数量，观察 [1]、[2] 及消息长度变化。本步与生产问答共享同一个组装器。'],
    ['generate','生成与引用','真实调用模型，然后核对回答和原文。','模型根据 Prompt 生成文字。程序能检查引用编号是否有效；数字、费用类别和城市是否正确仍需要逐项核对。','检查答案是否说清楚“每人每晚”“最高 650 元”，并区分住宿与餐饮。']
  ];
  const state = { index:0, run:null, results:{}, busy:false, config:null,
    settings:{size:220,overlap:40,query:'去上海出差，酒店每晚最高能报销多少钱？',topK:5,threshold:0,method:'llm',rerankSource:'retrieved',promptSource:'ranked',evidenceCount:3} };
  const node = (tag,cls,text) => { const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n; };
  async function api(path,body,method='POST') {
    let token='';try { token=sessionStorage.getItem('zhixu.rag.accessToken') || ''; } catch (_) {}
    const headers=token ? {Authorization:'Bearer '+token} : {};
    if(body!==undefined && !(body instanceof FormData)) headers['Content-Type']='application/json';
    const response=await fetch('/api/v1/tutorial'+path,{method,headers,credentials:'same-origin',body:body===undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),signal:AbortSignal.timeout(120000)});
    const data=await response.json();if(!response.ok)throw new Error(data.detail || '请求失败：HTTP '+response.status);return data;
  }
  function error(message) { $('error').textContent=message || ''; $('error').hidden=!message; }
  function field(label,key,type='text',options) {
    const wrap=node('label','field');wrap.append(node('span','',label));let input;
    if(options) { input=node('select');for(const [value,text] of options){const option=node('option','',text);option.value=value;input.append(option);} }
    else { input=node('input');input.type=type;if(type==='number')input.step=key==='threshold' ? '.05' : '1'; }
    input.id='setting-'+key;input.value=state.settings[key];
    const update=()=>{state.settings[key]=type==='number'?Number(input.value):input.value;};
    input.addEventListener('input',update);input.addEventListener('change',update);wrap.append(input);return wrap;
  }
  function render() {
    const [stage,title,summary,principle,tip]=lessons[state.index];
    $('steps').replaceChildren();lessons.forEach(([key,label],i)=>{
      const button=node('button','step'+(state.index===i?' active':'')+(state.results[key]?' done':''));
      button.append(node('span','num',String(i+1).padStart(2,'0')),node('strong','',label),node('span','',i<2||i===5?'本地':'模型'));
      button.disabled=state.busy;button.setAttribute('aria-current',state.index===i?'step':'false');button.onclick=()=>{state.index=i;error();render();};$('steps').append(button);
    });
    $('lesson-number').textContent='EXPERIMENT '+String(state.index+1).padStart(2,'0');$('lesson-title').textContent=title;
    $('lesson-summary').textContent=summary;$('principle').textContent=principle;$('experiment-tip').textContent=tip;
    $('controls').replaceChildren();const c=$('controls');
    if(stage==='chunk'){c.append(field('分块长度（字符）','size','number'),field('重叠长度（字符）','overlap','number'));}
    if(stage==='retrieve'){c.append(field('问题','query'),field('Top-K','topK','number'),field('Cosine 阈值 [-1, 1]','threshold','number'));}
    if(stage==='rerank'){c.append(field('问题','query'),field('候选来源','rerankSource','text',[['retrieved','向量召回的候选'],['fixture','独立候选集（无需入库）']]),field('重排方式','method','text',[['llm','真实 LLM 重排 · DeepSeek'],['bm25','离线 BM25 对照 · 非神经网络'],['configured','已配置的专用重排服务']]));}
    if(stage==='prompt'){c.append(field('问题','query'),field('证据来源','promptSource','text',[['ranked','重排后的候选'],['retrieved','向量召回的候选'],['fixture','独立证据集（无需模型）']]),field('证据数量','evidenceCount','number'));}
    const previews={parse:state.run?.sourcePreview,chunk:state.results.parse?.output.sections.map(s=>s.text).join('\n\n'),embed:state.results.chunk?.output.chunks.map(x=>'#'+x.chunk.index+' '+x.chunk.text).join('\n\n'),retrieve:'问题向量会在点击后生成。页面会把 Qdrant 分数与本地重新计算的 Cosine 放在一起比较。',rerank:'输出会显示每个片段排序前后的名次。独立候选集可以跳过 Embedding 和向量入库。',prompt:'模型实际接收的是 messages：system 规则与 user 问题/证据分开。这里会展示完整消息内容。',generate:'使用第 06 步刚刚展示的 Prompt 调用模型，不会自动添加其他资料。'};
    $('input-preview').textContent=previews[stage] || '先准备一份示例资料，再逐步运行实验。';
    $('execute').disabled=state.busy || !state.config;
    $('execute').textContent=state.busy?'实验运行中…':'运行当前实验 →';
    $('execution-hint').textContent=['embed','retrieve','generate'].includes(stage)?'本步调用真实云端模型 API；请等待结果。':'每一步都有独立入口，前置步骤不足时会给出提示。';
    for(const id of ['prepare','clear','run-all','upload','sample']) $(id).disabled=state.busy || (id==='clear'&&!state.run);
    for(const input of c.querySelectorAll('input,select'))input.disabled=state.busy;
    $('run-status').textContent=state.run?'实验 '+state.run.runId.slice(0,8)+' · 独立索引':'尚未创建实验';
    $('export-all').disabled=!Object.keys(state.results).length || state.busy;
    renderOutput(stage);document.body.classList.toggle('busy',state.busy);
  }
  function metricRow(values) { const row=node('div','metric-row');values.forEach(([label,value])=>{const item=node('div','metric');item.append(node('b','',String(value)),node('span','',label));row.append(item);});return row; }
  function piece(title,text,extra) { const card=node('div','piece');const head=node('div','piece-header');head.append(node('span','',title));if(extra)head.append(node('span','',extra));card.append(head,node('p','',text));return card; }
  function location(hit) { return hit.page?'第 '+hit.page+' 页':hit.section || '正文'; }
  function renderOutput(stage) {
    const result=state.results[stage],out=$('output');out.replaceChildren();$('output-meta').textContent='';$('export-stage').disabled=!result;
    $('raw-json').textContent=result?JSON.stringify(result,null,2):'尚无结果';
    if(!result){out.append(node('div','empty','运行这一步，观察数据如何变化。'));return;}
    $('output-meta').textContent=result.elapsedMs+' ms · '+result.mode;const data=result.output;
    if(stage==='parse'){out.append(metricRow([['来源段落',data.sectionCount],['提取字符',data.characters]]));data.sections.forEach((s,i)=>out.append(piece('SECTION '+(i+1)+' · '+location(s),s.text)));}
    if(stage==='chunk'){out.append(metricRow([['片段数量',data.chunkCount],['分块长度',result.input.size],['重叠参数',result.input.overlap]]));data.chunks.forEach(({chunk,characters,sharedPrefixCharacters})=>{
      const card=piece('CHUNK '+chunk.index+' · '+location(chunk),'',characters+' 字符');const p=card.querySelector('p');
      if(sharedPrefixCharacters)p.append(node('mark','',chunk.text.slice(0,sharedPrefixCharacters)));p.append(document.createTextNode(chunk.text.slice(sharedPrefixCharacters)));out.append(card);
    });}
    if(stage==='embed'){out.append(metricRow([['实际维度',data.database.dimensions],['数据库点数',data.database.pointsCount]]));out.append(node('p','muted','Collection: '+data.database.collection));data.vectors.forEach(x=>{
      const card=piece('CHUNK '+x.chunkIndex,'L2 范数 '+x.vector.l2Norm.toFixed(4));const grid=node('div','vector');x.vector.first8.forEach(v=>grid.append(node('span','',v.toFixed(5))));card.append(grid);out.append(card);
    });}
    if(stage==='retrieve'){out.append(metricRow([['返回片段',data.hitCount],['问题向量维度',data.queryVector.dimensions]]));data.hits.forEach((hit,i)=>{
      const check=data.cosineChecks[i];const card=piece('#'+(i+1)+' · '+location(hit),hit.text,'Cosine '+hit.score.toFixed(4));
      card.append(node('p','muted','本地复算 '+check.recomputedCosine.toFixed(4)));const bar=node('div','score-line'),fill=node('span');fill.style.width=Math.max(0,Math.min(100,hit.score*100))+'%';bar.append(fill);card.append(bar);out.append(card);
    });if(!data.hitCount)out.append(node('div','empty','没有达到当前阈值的候选。尝试降低阈值或换一个问题。'));}
    if(stage==='rerank'){out.append(metricRow([['候选数量',data.hits.length],['排序方式',result.input.method==='llm'?'LLM':result.input.method==='bm25'?'BM25':'专用模型']]));data.comparison.forEach(row=>{
      const diff=row.beforeRank-row.afterRank;out.append(piece('原第 '+row.beforeRank+' → 新第 '+row.afterRank,row.hit.text,diff>0?'↑ '+diff:diff<0?'↓ '+(-diff):'顺序不变'));
    });}
    if(stage==='prompt'){out.append(metricRow([['使用证据',data.selectedCount],['消息字符数',data.characters]]));data.messages.forEach(m=>{const card=piece(m.role.toUpperCase(),'');card.querySelector('p').className='prompt-message';card.querySelector('p').textContent=m.content;out.append(card);});}
    if(stage==='generate'){out.append(node('div','answer',data.answer));out.append(node('p','muted',data.citationNumbersValid?'引用编号可对应本轮证据；事实仍需核对。':'回答没有有效引用编号，请核对原文。'));data.citations.forEach((hit,i)=>out.append(piece('['+(i+1)+'] '+hit.filename+' · '+location(hit),hit.text)));}
    const notes=node('div','notes');result.notes.forEach(text=>notes.append(node('p','',text)));out.append(notes);
  }
  async function prepare(sample,upload) {
    if(state.run)await api('/runs/'+state.run.runId,undefined,'DELETE');
    state.run=null;state.results={};state.index=0;
    state.run=upload?await api('/runs/upload',upload):await api('/runs',{sample});
  }
  async function execute(stage) {
    if(!state.run)await prepare($('sample').value);
    const s=state.settings;let body={};
    if(stage==='chunk')body={size:s.size,overlap:s.overlap};
    if(stage==='retrieve')body={query:s.query,topK:s.topK,threshold:s.threshold};
    if(stage==='rerank')body={query:s.query,method:s.method,source:s.rerankSource};
    if(stage==='prompt')body={question:s.query,topK:s.evidenceCount,source:s.promptSource};
    const result=await api('/runs/'+state.run.runId+'/'+stage,body);
    const index=lessons.findIndex(x=>x[0]===stage);
    // An upstream rerun invalidates dependent results; local output must match server state.
    if(stage!=='embed' || !state.results.embed)for(let i=index+1;i<lessons.length;i++)delete state.results[lessons[i][0]];
    state.results[stage]=result;return result;
  }
  async function busy(action) {if(state.busy)return;state.busy=true;error();render();try{await action();}catch(e){error(e.name==='TimeoutError'?'等待超时，请查询实验状态后再操作。':e.message);}finally{state.busy=false;render();}}
  function download(data,name){const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json;charset=utf-8'}));const a=node('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  $('execute').onclick=()=>busy(()=>execute(lessons[state.index][0]));
  $('prepare').onclick=()=>busy(()=>prepare($('sample').value));
  $('upload').onchange=()=>{const file=$('upload').files[0];if(!file)return;const form=new FormData();form.append('file',file);$('upload').value='';busy(()=>prepare(null,form));};
  $('sample').onchange=()=>{$('download-sample').href='/api/v1/tutorial/samples/'+$('sample').value;};
  $('download-sample').onclick=event=>{event.preventDefault();busy(async()=>{let token='';try{token=sessionStorage.getItem('zhixu.rag.accessToken')||'';}catch(_){}const response=await fetch($('download-sample').href,{headers:token?{Authorization:'Bearer '+token}:{}});if(!response.ok)throw new Error('下载失败，请检查服务令牌');const url=URL.createObjectURL(await response.blob());const a=node('a');a.href=url;a.download='demo-handbook.'+$('sample').value;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});};
  $('clear').onclick=()=>busy(async()=>{await api('/runs/'+state.run.runId,undefined,'DELETE');state.run=null;state.results={};});
  $('run-all').onclick=()=>busy(async()=>{await prepare($('sample').value);state.settings.rerankSource='retrieved';state.settings.promptSource='ranked';for(let i=0;i<lessons.length;i++){state.index=i;render();await execute(lessons[i][0]);render();}});
  $('export-stage').onclick=()=>download(state.results[lessons[state.index][0]],'rag-'+lessons[state.index][0]+'.json');
  $('export-all').onclick=()=>download({run:state.run,stages:state.results},'rag-tutorial-'+state.run.runId+'.json');
  render();api('/config',undefined,'GET').then(config=>{state.config=config;$('service-status').textContent='教学服务已启用 · Embedding '+(config.embeddingConfigured?config.embeddingModel+' / '+config.embeddingDimensions+' 维':'未配置')+' · 聊天 '+(config.chatConfigured?config.chatModel:'未配置')+' · 专用重排 '+(config.rerankerConfigured?'已配置':'未配置，可用真实 LLM 或离线对照');render();}).catch(e=>{$('service-status').textContent='教学接口未启用或无法访问。使用 Start-Local.ps1 启动，或设置 RAG_TUTORIAL_ENABLED=true。';error(e.message);});
})();
