// Browser-local practice. No alarm, sync, permission or sensor effects.
let practiceCleanup=()=>{};
const practiceInt=(min,max)=>Math.floor(Math.random()*(max-min))+min;
function practiceShuffle(items){const a=[...items];for(let i=a.length-1;i>0;i--){const j=practiceInt(0,i+1);[a[i],a[j]]=[a[j],a[i]];}return a;}
function startChallengePractice(){
 practiceCleanup();practiceCleanup=()=>{};
 const timers=new Set();const later=(fn,ms)=>{const id=setTimeout(()=>{timers.delete(id);fn();},ms);timers.add(id);return id;};practiceCleanup=()=>{timers.forEach(clearTimeout);timers.clear();};
 const type=editorDraft.challengeType;const area=$('challengePractice');if(!area)return;
 area.innerHTML='<p>Browser-only practice. Does not save, dismiss or test a phone alarm.</p><div id="practicePrompt"></div><div id="practiceControls" style="display:flex;flex-wrap:wrap;gap:8px"></div><p id="practiceFeedback" role="status"></p>';
 area.style.paddingBottom='24px';
 const prompt=$('practicePrompt'),controls=$('practiceControls'),feedback=$('practiceFeedback');
 const done=()=>{feedback.textContent='Practice complete. No phone alarm was changed.';controls.querySelectorAll('button,input').forEach(e=>e.disabled=true);practiceCleanup();};
 const button=(text,fn)=>{const b=document.createElement('button');b.type='button';b.className='secondary';b.textContent=text;b.onclick=fn;controls.append(b);return b;};
 const textAnswer=(text,answer)=>{prompt.textContent=text;const input=document.createElement('input');input.type='text';input.autocomplete='off';input.setAttribute('aria-label','Practice answer');controls.append(input);button('Check',()=>input.value===answer?done():feedback.textContent='Try again.');};
 if(type==='NONE'){prompt.textContent='No challenge selected.';return;}
 if(type.startsWith('MATH_')){
  let a,b,c,answer,expression;
  if(type==='MATH_EASY'){a=practiceInt(2,20);b=practiceInt(2,20);const op=practiceInt(0,3);answer=op===0?a+b:op===1?Math.max(a,b)-Math.min(a,b):a*b;expression=op===0?`${a} + ${b}`:op===1?`${Math.max(a,b)} - ${Math.min(a,b)}`:`${a} x ${b}`;}
  else if(type==='MATH_MEDIUM'){a=practiceInt(10,50);b=practiceInt(2,15);c=practiceInt(2,10);answer=a+b*c;expression=`${a} + (${b} x ${c})`;}
  else{a=practiceInt(50,200);b=practiceInt(10,100);c=practiceInt(2,20);answer=a+b-c;expression=`${a} + ${b} - ${c}`;}
  prompt.textContent=expression+' = ?';const options=new Set([answer]);while(options.size<4){const n=answer+practiceInt(-10,11);if(n>=0)options.add(n);}practiceShuffle(options).forEach(n=>button(String(n),()=>n===answer?done():feedback.textContent='Try again.'));return;
 }
 if(type==='TYPING'||type==='DATE_BACKWARDS'){
  const phrases=[...PRACTICE_DATA.typing,...String(phoneSettings.customTypingPhrases||'').split('\n').filter(Boolean)];
  if(type==='TYPING'){const phrase=phrases[practiceInt(0,phrases.length)];textAnswer('Type exactly: '+phrase,phrase);}
  else{const d=new Date(),date=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;textAnswer('Reverse the characters of '+date+' (browser local date)',[...date].reverse().join(''));}return;
 }
 if(type==='SEQUENCE'){
  const numbers=practiceShuffle(Array.from({length:99},(_,i)=>i+1)).slice(0,6),order=[...numbers].sort((a,b)=>a-b);let index=0;prompt.textContent='Tap numbers in ascending order';numbers.forEach(n=>button(String(n),()=>{if(n!==order[index]){feedback.textContent='Wrong order. Continue from the next lowest number.';return;}index++;controls.querySelectorAll('button').forEach(b=>{if(b.textContent===String(n))b.disabled=true;});if(index===6)done();}));return;
 }
 if(type==='MEMORY_PATTERN'||type==='SIMON_SAYS'){
  const simon=type==='SIMON_SAYS',count=simon?4:9,pattern=simon?Array.from({length:practiceInt(4,7)},()=>practiceInt(0,4)):practiceShuffle(Array.from({length:9},(_,i)=>i)).slice(0,4);let index=0;
  prompt.textContent='Remember positions: '+pattern.map(n=>n+1).join(' → ');const buttons=Array.from({length:count},(_,n)=>button(String(n+1),()=>{if(n!==pattern[index]){index=0;feedback.textContent='Wrong pattern. Start again.';return;}index++;if(index===pattern.length)done();}));buttons.forEach(b=>b.disabled=true);
  const timer=later(()=>{if(!prompt.isConnected)return;prompt.textContent='Repeat the pattern';buttons.forEach(b=>b.disabled=false);},simon?pattern.length*600:2500);return;
 }
 if(type==='STROOP'){
  const names=['RED','GREEN','BLUE','YELLOW'],colors=['#ff7676','#89e89a','#76b5ff','#ffdf7a'],ink=practiceInt(0,4);prompt.textContent=names[(ink+practiceInt(1,4))%4];prompt.style.color=colors[ink];prompt.style.fontSize='32px';practiceShuffle([0,1,2,3]).forEach(n=>button(names[n],()=>n===ink?done():feedback.textContent='Choose the ink color, not the word.'));return;
 }
 if(type==='ROCK_PAPER_SCISSORS'){
  let wins=0;prompt.textContent='First to 3 wins';['Rock','Paper','Scissors'].forEach((name,n)=>button(name,()=>{const computer=practiceInt(0,3),win=(n-computer+3)%3===1;if(win)wins++;feedback.textContent=`Computer: ${['Rock','Paper','Scissors'][computer]}. ${n===computer?'Tie':win?'Win':'Loss'} · ${wins}/3 wins`;if(wins===3)done();}));return;
 }
 if(type==='EMOJI_MEMORY'){
  const emojis=['🐑','🌞','⏰','🍋','🌊','⭐','🌸','🌙'],cards=practiceShuffle([...emojis,...emojis]);let first=null,matches=0,busy=true;prompt.textContent='Remember and match 8 pairs';const buttons=cards.map((emoji,i)=>button(emoji,()=>{if(busy||buttons[i].disabled||first===i)return;buttons[i].textContent=emoji;if(first===null){first=i;return;}if(cards[first]===emoji){buttons[first].disabled=true;buttons[i].disabled=true;first=null;if(++matches===8)done();}else{busy=true;const previous=first;first=null;const t=later(()=>{buttons[previous].textContent='?';buttons[i].textContent='?';busy=false;},700);}}));const timer=later(()=>{buttons.forEach(b=>b.textContent='?');busy=false;},3000);return;
 }
 if(type==='SPOT_DIFFERENCE'){
  const symbols=['●','▲','■','★','♥','◆'],base=Array.from({length:16},()=>practiceInt(0,6)),changed=practiceInt(0,16);prompt.textContent='Compare the two 4x4 grids. Tap the changed tile in the second grid.';
  for(let grid=0;grid<2;grid++){const wrap=document.createElement('div');wrap.style.cssText='display:grid;grid-template-columns:repeat(4,minmax(0,1fr));width:100%;gap:4px';base.forEach((n,i)=>{const b=button(symbols[grid===1&&i===changed?(n+1)%6:n],()=>{if(grid===1){if(i===changed)done();else feedback.textContent='Try another tile.';}});wrap.append(b);});controls.append(wrap);}return;
 }

 if(type==='TYPING_SPEED'){
  const phrase=PRACTICE_DATA.typingSpeed[practiceInt(0,PRACTICE_DATA.typingSpeed.length)];prompt.textContent=`Type at least 15 words/min with at most 2 word errors: ${phrase}`;
  const input=document.createElement('input');input.setAttribute('aria-label','Typing speed answer');input.autocomplete='off';controls.append(input);let start=0;input.oninput=()=>{if(!start)start=performance.now();};
  button('Check',()=>{const words=input.value.trim().split(/\s+/),target=phrase.split(/\s+/),errors=Math.max(words.length,target.length)-target.filter((w,i)=>w===words[i]).length,wpm=words.length/Math.max((performance.now()-start)/60000,0.001);if(start&&wpm>=15&&errors<=2)done();else feedback.textContent=`${wpm.toFixed(1)} words/min, ${errors} errors. Try again.`;});return;
 }
 if(type==='WORDLE'){
  const target=PRACTICE_DATA.wordle[practiceInt(0,PRACTICE_DATA.wordle.length)];let attempts=0;prompt.textContent='Guess a 5-letter word in 6 attempts. Green = correct position, yellow = elsewhere, gray = absent.';
  const input=document.createElement('input');input.maxLength=5;input.setAttribute('aria-label','Five letter guess');controls.append(input);const guesses=document.createElement('div');guesses.style.width='100%';controls.append(guesses);
  button('Guess',()=>{const guess=input.value.trim().toUpperCase();if(!/^[A-Z]{5}$/.test(guess)){feedback.textContent='Enter 5 letters';return;}const colors=Array(5).fill('gray'),remaining={};for(let i=0;i<5;i++){if(guess[i]===target[i])colors[i]='green';else remaining[target[i]]=(remaining[target[i]]||0)+1;}for(let i=0;i<5;i++)if(colors[i]!=='green'&&remaining[guess[i]]>0){colors[i]='yellow';remaining[guess[i]]--;}const row=document.createElement('p');row.textContent=[...guess].map((c,i)=>`${c} (${colors[i]})`).join(' · ');guesses.append(row);input.value='';if(guess===target)done();else if(++attempts===6){feedback.textContent='Answer: '+target+'. Start new practice to try again.';controls.querySelectorAll('input,button').forEach(e=>e.disabled=true);}});return;
 }
 if(type==='CHESS_MATE'){
  const puzzle=PRACTICE_DATA.chess[practiceInt(0,PRACTICE_DATA.chess.length)];prompt.textContent='White to move: choose mate in one. Uppercase pieces are white.';
  const board=document.createElement('div');board.style.cssText='display:grid;grid-template-columns:repeat(8,minmax(0,1fr));width:100%';puzzle.board.forEach((piece,i)=>{const cell=document.createElement('span');cell.textContent=piece;cell.style.cssText=`text-align:center;padding:5px 0;background:${(Math.floor(i/8)+i)%2?'#29445c':'#1c2e40'}`;board.append(cell);});controls.append(board);puzzle.choices.forEach(move=>button(move,()=>move===puzzle.answer?done():feedback.textContent='Try another move.'));return;
 }
 if(type==='RSVP_READING'){
  const words=PRACTICE_DATA.rsvp[practiceInt(0,PRACTICE_DATA.rsvp.length)],answers=words.filter(w=>w.length>=5),answer=answers[practiceInt(0,answers.length)],others=practiceShuffle([...new Set(PRACTICE_DATA.rsvp.flat().filter(w=>w!==answer&&w.length>=5))]).slice(0,3);let index=-1,timer;
  const show=()=>{if(!prompt.isConnected)return;if(++index<words.length){prompt.textContent=words[index];timer=later(show,260);}else{prompt.textContent='Which word appeared?';practiceShuffle([answer,...others]).forEach(word=>button(word,()=>word===answer?done():feedback.textContent='Try again.'));}};prompt.textContent='Watch the word stream';timer=later(show,500);return;
 }
 if(type==='PVT'){
  let trial=0,ready=0,total=0,timer;prompt.textContent='5 trials, average response at most 500ms. Browser timing is approximate.';const tap=button('Start trial',()=>{if(ready){total+=performance.now()-ready;ready=0;if(++trial===5){if(total/5<=500)done();else{feedback.textContent='Too slow. Start another practice.';tap.disabled=true;}return;}tap.textContent='Start trial';feedback.textContent=`${trial}/5 · average ${Math.round(total/trial)}ms`;}else if(tap.textContent==='Wait'){clearTimeout(timer);feedback.textContent='False start. Restart trial.';tap.textContent='Start trial';}else{tap.textContent='Wait';timer=later(()=>{if(!tap.isConnected)return;ready=performance.now();tap.textContent='Tap now';},practiceInt(1000,4000));}});return;
 }
 if(type==='MAZE'){
  let position=0;const path=new Set([0,1,2,3,4,9,14,19,24]),walls=new Set(practiceShuffle(Array.from({length:25},(_,i)=>i).filter(i=>!path.has(i))).slice(0,7));prompt.textContent='Move to the goal (24) using adjacent cells. Generated browser maze, not a phone test.';
  const grid=document.createElement('div');grid.style.cssText='display:grid;grid-template-columns:repeat(5,minmax(0,1fr));width:100%;gap:4px';const cells=Array.from({length:25},(_,i)=>{const b=button(walls.has(i)?'#':String(i),()=>{if(walls.has(i)||Math.abs(Math.floor(i/5)-Math.floor(position/5))+Math.abs(i%5-position%5)!==1){feedback.textContent='Choose an adjacent open cell';return;}cells[position].textContent=String(position);position=i;b.textContent='You';if(i===24)done();});grid.append(b);return b;});cells[0].textContent='You';cells[24].textContent='Goal';controls.append(grid);return;
 }
 if(type==='COUNT_SHEEP'){
  const target=practiceInt(6,11);let count=0;prompt.textContent=`Count exactly ${target} sheep. Browser-local counting version; no animated native flock.`;
  button('Sheep 🐑',()=>{count++;feedback.textContent=`${count} sheep`;});button('Goat 🐐',()=>{count=Math.max(0,count-1);feedback.textContent=`Goat penalty: ${count} sheep`;});button('Finish',()=>count===target?done():feedback.textContent='Count does not match.');return;
 }
 prompt.textContent='This challenge needs Android hardware, native recognition, or its full native puzzle engine. Web configuration syncs, but this browser does not claim to test it. Practice on your phone.';
}
document.addEventListener('visibilitychange',()=>{if(document.hidden)practiceCleanup();});
document.addEventListener('click',event=>{if(event.target.closest('[data-practice-challenge]'))startChallengePractice();});
