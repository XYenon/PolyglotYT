// Compare rendered presets against fishjar/kiss-translator df3e1e3, not only template strings.
const fs=require('fs'), vm=require('vm');
const root=process.argv[2]||'build/kiss-translator-review';
const api=fs.readFileSync(root+'/src/config/api.js','utf8');
const trans=fs.readFileSync(root+'/src/apis/trans.js','utf8');
const context=vm.createContext({});
for(const [symbol,name] of Object.entries({TITLE:'title',DESCRIPTION:'description',SUMMARY:'summary',CONTEXT:'context',TONE:'tone',GLOSSARY:'glossary',FROM:'from',TO:'to',FROM_LANG:'fromLang',TO_LANG:'toLang',TEXT:'text',SEGMENTS:'segments'}))context['INPUT_PLACE_'+symbol]='{{'+name+'}}';
for(const name of ['defaultNobatchPrompt','defaultNobatchUserPrompt','defaultNobatchPromptConcise','defaultNobatchUserPromptConcise','defaultSystemPromptJson','defaultBatchUserPromptJson','defaultSystemPromptXml','defaultBatchUserPromptXml','defaultSystemPromptLines','defaultBatchUserPromptLines','defaultSubtitlePrompt']){
 const match=api.match(new RegExp('^(?:export )?const '+name+' = (`(?:\\\\.|[^`])*`|"[^\"]*");','ms'));
 if(!match)throw Error(name);
 vm.runInContext(`var ${name} = ${match[1]};`,context);
}
const utils=fs.readFileSync(root+'/src/libs/utils.js','utf8');
vm.runInContext(utils.slice(utils.indexOf('export function parseAITerms'),utils.indexOf('\n/**',utils.indexOf('export function parseAITerms'))).replace('export ',''),context);
vm.runInContext(trans.slice(trans.indexOf('const genSystemPrompt ='),trans.indexOf('/**\n * 强健的大模型')).replace('export const buildSubtitleSystemPrompt','const buildSubtitleSystemPrompt'),context);
const fixtures=[];
for(const docInfo of [{},{title:'My video',description:'A description',summary:'A summary',context:'Adjacent text'}]){
 for(const aiTerms of ['', 'React,React;component,组件;unchanged']){
  for(const tone of ['', 'formal']){
   const texts=['A <b>React</b> component.','Two lines\nwith a "quote".'];
   const common={docInfo,aiTerms,tone,texts,from:'English',to:'Simplified Chinese',fromLang:'en',toLang:'zh-CN'};
   context.args=common;
   for(const mode of ['concise','legacy']){
    context.mode=mode;
    const value=vm.runInContext(`(()=>{const nobatchPrompt=mode==='concise'?defaultNobatchPromptConcise:defaultNobatchPrompt;const nobatchUserPrompt=mode==='concise'?defaultNobatchUserPromptConcise:defaultNobatchUserPrompt;return {system:genSystemPrompt({...args,systemPrompt:nobatchPrompt}),user:genUserPrompt({...args,nobatchUserPrompt,useBatchFetch:false})}})()`,context);
    fixtures.push({kind:'single',mode,...common,...value});
   }
   for(const mode of ['json','xml','line']){
    context.mode=mode;
    const value=vm.runInContext(`(()=>{const prompts={json:[defaultSystemPromptJson,defaultBatchUserPromptJson],xml:[defaultSystemPromptXml,defaultBatchUserPromptXml],line:[defaultSystemPromptLines,defaultBatchUserPromptLines]};return {system:genSystemPrompt({...args,systemPrompt:prompts[mode][0]}),user:genUserPrompt({...args,batchUserPrompt:prompts[mode][1],batchProtocol:mode,useBatchFetch:true})}})()`,context);
    fixtures.push({kind:'batch',mode,...common,...value});
   }
   const value=vm.runInContext(`buildSubtitleSystemPrompt({...args,subtitlePrompt:defaultSubtitlePrompt,from:args.fromLang,to:args.toLang})`,context);
   fixtures.push({kind:'subtitle',mode:'boundary-v3',...common,system:value});
  }
 }
}
fs.writeFileSync('app/src/test/resources/kiss-prompts.json',JSON.stringify(fixtures));
console.log(`Generated ${fixtures.length} rendered prompt cases from kiss-translator df3e1e3`);
