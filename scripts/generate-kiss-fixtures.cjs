// Reference: fishjar/kiss-translator df3e1e3fe1ada6f81e0081e4502eb2faecbd602d.
// Run from the repository root, passing the reference checkout path if needed.
const fs=require('fs'); const vm=require('vm');
const upstream=(process.argv[2] || 'build/kiss-translator-review')+'/src/subtitle/';
const context=vm.createContext({logger:{debug(){},info(){}}});
const clean=source=>source.replace(/^import[\s\S]*?;\s*/gm,'').replace(/export\s*\{[\s\S]*?\};/g,'').replace(/\bexport /g,'');
vm.runInContext(clean(fs.readFileSync(upstream+'subtitleTextClassification.js','utf8')),context);
vm.runInContext(clean(fs.readFileSync(upstream+'sentenceBreaker.js','utf8')),context);
// The rule module's language list has the same name as the boundary module, which is not loaded here.
vm.runInContext(clean(fs.readFileSync(upstream+'youtubeSubtitleProcessing.js','utf8')),context);
let seed=179;
const random=()=>{seed=(seed*1664525+1013904223)>>>0;return seed/4294967296;};
const fixtures=[];
for(let test=0;test<150;test++){
 const lang=['en','zh','ja','es','de'][test%5]; let time=0; const events=[];
 for(let index=0;index<15+test%65;index++){
  const count=1+Math.floor(random()*6);let offset=0; const segs=[];
  for(let word=0;word<count;word++){
   const isCoarse=test%7===0;
   let utf8=lang==='zh'?'词语':lang==='ja'?'字幕':(['hello','World','I','NASA','although','so','>> Speaker'][Math.floor(random()*7)]);
   if(isCoarse&&word===0)utf8+=' another long sentence fragment';
   if(random()<0.11)utf8+='.';else if(random()<0.08)utf8+=',';
   if(random()<0.045)utf8='[Music]';
   if(random()<0.03)utf8='';
   segs.push({utf8,tOffsetMs:offset,...(isCoarse?{acAsrConf:0.9}:{})});offset+=30+Math.floor(random()*900);
  }
  const event={tStartMs:time,dDurationMs:offset+Math.floor(random()*1000),segs};events.push(event);
  if(random()<0.06)events.push(JSON.parse(JSON.stringify(event)));
  time+=offset+(random()<0.1?1600:Math.floor(random()*180));
  if(random()<0.1)events.push({tStartMs:time-50,dDurationMs:0,aAppend:1,segs:[{utf8:'\n'}]});
 }
 context.raw=events;context.lang=lang;
 const expected=vm.runInContext(`(()=>{const p=prepareTimedTextEvents(raw,lang);return {words:p.flatEvents,rule:runBuiltinSegmentation({events:p.events,flatEvents:p.flatEvents,fromLang:lang,longSentenceThreshold:100}),statistical:runBuiltinSegmentation({events:p.events,flatEvents:p.flatEvents,fromLang:lang,mode:'statistical',longSentenceThreshold:100}),chunks:splitEventsIntoChunks(p.flatEvents,1000)}})()`,context);
 fixtures.push({language:lang,events,...expected});
}
fs.mkdirSync('app/src/test/resources',{recursive:true});
fs.writeFileSync('app/src/test/resources/kiss-subtitles.json.gz',require('zlib').gzipSync(JSON.stringify(fixtures)));
console.log(`Generated ${fixtures.length} cases from kiss-translator df3e1e3`);
