(function(){
'use strict';
var $=function(i){return document.getElementById(i);};
var root=document.documentElement;
var store={get:function(k){try{return localStorage.getItem(k);}catch(e){return null;}},
           set:function(k,v){try{localStorage.setItem(k,v);}catch(e){}}};
var reduce=false;try{reduce=window.matchMedia('(prefers-reduced-motion: reduce)').matches;}catch(e){}

/* ===== AndroidBridge ===== */
var Bridge={call:function(n){var a=[].slice.call(arguments,1);
  try{var b=window.AndroidBridge;if(b&&typeof b[n]==='function'){b[n].apply(b,a);return true;}}catch(e){}
  return false;}};

/* ===== inline flags (as sent — drawn SVG flags, no emoji) ===== */
function bands(dir,list){var t=0,i;for(i=0;i<list.length;i++)t+=list[i][1];
  var p=0,o='';for(i=0;i<list.length;i++){var w=list[i][1]/t*(dir==='h'?30:60);
    o+=dir==='h'?'<rect y="'+p.toFixed(2)+'" width="60" height="'+(w+.03).toFixed(2)+'" fill="'+list[i][0]+'"/>'
               :'<rect x="'+p.toFixed(2)+'" width="'+(w+.03).toFixed(2)+'" height="30" fill="'+list[i][0]+'"/>';
    p+=w;}return o;}
function nordic(bg,c1,c2){var s='<rect width="60" height="30" fill="'+bg+'"/>'+
  '<rect x="17" width="9" height="30" fill="'+c1+'"/><rect y="10.5" width="60" height="9" fill="'+c1+'"/>';
  if(c2)s+='<rect x="19.5" width="4" height="30" fill="'+c2+'"/><rect y="13" width="60" height="4" fill="'+c2+'"/>';
  return s;}
function star(cx,cy,r,f){var p='',i,rr,a;for(i=0;i<10;i++){rr=(i%2)?r*.42:r;a=-Math.PI/2+i*Math.PI/5;
  p+=(i?'L':'M')+(cx+rr*Math.cos(a)).toFixed(2)+' '+(cy+rr*Math.sin(a)).toFixed(2);}
  return '<path d="'+p+'Z" fill="'+f+'"/>';}
function disc(cx,cy,r,f){return '<circle cx="'+cx+'" cy="'+cy+'" r="'+r+'" fill="'+f+'"/>';}
function cres(cx,cy,r,f,bg,dx){return disc(cx,cy,r,f)+disc(cx+dx,cy,r*.82,bg);}
var GB='<rect width="60" height="30" fill="#012169"/>'+
 '<path d="M0 0 60 30M60 0 0 30" stroke="#fff" stroke-width="6"/>'+
 '<path d="M0 0 60 30M60 0 0 30" stroke="#C8102E" stroke-width="3.2"/>'+
 '<path d="M30 0V30M0 15H60" stroke="#fff" stroke-width="10"/>'+
 '<path d="M30 0V30M0 15H60" stroke="#C8102E" stroke-width="6"/>';
function canton(inner,x,y,w){return '<g transform="translate('+x+','+y+') scale('+(w/60)+')">'+inner+'</g>';}
var SPECIAL={
 us:function(){var h=30/13,ch=7*h,s='<rect width="60" height="30" fill="#fff"/>',i,r,c,cols,x,y;
   for(i=0;i<7;i++)s+='<rect y="'+(i*2*h).toFixed(2)+'" width="60" height="'+h.toFixed(2)+'" fill="#B22234"/>';
   s+='<rect width="26" height="'+ch.toFixed(2)+'" fill="#3C3B6E"/>';
   for(r=0;r<5;r++){cols=(r%2)?5:6;for(c=0;c<cols;c++){x=(26/(cols*2))*(c*2+1);y=(ch/10)*(r*2+1);s+=star(x,y,1,'#fff');}}
   return s;},
 gb:function(){return GB;},
 ca:function(){return bands('v',[['#D80621',1],['#fff',2],['#D80621',1]])+
   '<path d="M30 6l1.9 3.6 3.4-1-1 3.6 3.6.6-2.7 2.5 1 2.6-3.6-.8-.6 3.9h-4l-.6-3.9-3.6.8 1-2.6-2.7-2.5 3.6-.6-1-3.6 3.4 1z" fill="#D80621"/>';},
 jp:function(){return '<rect width="60" height="30" fill="#fff"/>'+disc(30,15,8.4,'#BC002D');},
 sg:function(){var s='<rect width="60" height="30" fill="#fff"/><rect width="60" height="15" fill="#ED2939"/>'+cres(15,7.5,5,'#fff','#ED2939',2.2),i,a;
   for(i=0;i<5;i++){a=-Math.PI/2+i*2*Math.PI/5;s+=star(25+3.6*Math.cos(a),7.5+3.6*Math.sin(a),1.2,'#fff');}return s;},
 au:function(){var s='<rect width="60" height="30" fill="#00247D"/>'+canton(GB,0,0,30)+star(15,23,3.4,'#fff');
   s+=star(45,7,2,'#fff')+star(52,14,2,'#fff')+star(45,22,2,'#fff')+star(39,13,1.6,'#fff')+star(48,17,1.1,'#fff');return s;},
 nz:function(){return '<rect width="60" height="30" fill="#00247D"/>'+canton(GB,0,0,30)+
   star(45,7,2.2,'#CC142B')+star(52,14,2.2,'#CC142B')+star(45,23,2.2,'#CC142B')+star(38,15,1.8,'#CC142B');},
 ch:function(){return '<rect width="60" height="30" fill="#D52B1E"/><rect x="26" y="6" width="8" height="18" fill="#fff"/><rect x="21" y="11" width="18" height="8" fill="#fff"/>';},
 in:function(){return bands('h',[['#FF9933',1],['#fff',1],['#138808',1]])+
   '<circle cx="30" cy="15" r="4" fill="none" stroke="#000080" stroke-width="1"/>'+disc(30,15,1,'#000080');},
 br:function(){return '<rect width="60" height="30" fill="#009C3B"/><path d="M30 3 55 15 30 27 5 15Z" fill="#FFDF00"/>'+
   disc(30,15,7,'#002776')+'<path d="M23.5 13.5q6.5-2 13 1.6" stroke="#fff" stroke-width="1.6" fill="none"/>';},
 kr:function(){return '<rect width="60" height="30" fill="#fff"/>'+disc(30,15,6.5,'#CD2E3A')+
   '<path d="M23.5 15a6.5 6.5 0 0 0 13 0a3.25 3.25 0 0 0-6.5 0a3.25 3.25 0 0 1-6.5 0Z" fill="#0047A0"/>'+
   '<g stroke="#000" stroke-width="1.2"><path d="M12 8l4 2.6M11 10.4l4 2.6M44 19.4l4 2.6M45 17l4 2.6"/></g>';},
 tr:function(){return '<rect width="60" height="30" fill="#E30A17"/>'+cres(24,15,7,'#fff','#E30A17',2.4)+star(35,15,3,'#fff');},
 pt:function(){return bands('v',[['#046A38',2],['#DA291C',3]])+disc(24,15,5.5,'#FFE900')+disc(24,15,3,'#DA291C')+disc(24,15,1.6,'#fff');},
 cz:function(){return bands('h',[['#fff',1],['#D7141A',1]])+'<path d="M0 0 30 15 0 30Z" fill="#11457E"/>';},
 gr:function(){var s='<rect width="60" height="30" fill="#0D5EAF"/>',i;
   for(i=1;i<9;i+=2)s+='<rect y="'+(i*30/9).toFixed(2)+'" width="60" height="'+(30/9).toFixed(2)+'" fill="#fff"/>';
   s+='<rect width="16.7" height="16.7" fill="#0D5EAF"/><rect x="6.3" width="4.1" height="16.7" fill="#fff"/><rect y="6.3" width="16.7" height="4.1" fill="#fff"/>';return s;},
 il:function(){return '<rect width="60" height="30" fill="#fff"/><rect y="3" width="60" height="4" fill="#0038B8"/><rect y="23" width="60" height="4" fill="#0038B8"/>'+
   '<path d="M30 8.5l5 8.6H25Z M30 21.5l-5-8.6h10Z" fill="none" stroke="#0038B8" stroke-width="1.3"/>';},
 ae:function(){return bands('h',[['#00732F',1],['#fff',1],['#000',1]])+'<rect width="16" height="30" fill="#FF0000"/>';},
 sa:function(){return '<rect width="60" height="30" fill="#006C35"/><rect x="14" y="11" width="32" height="3" rx="1.5" fill="#fff"/><rect x="16" y="19" width="26" height="2" rx="1" fill="#fff"/>';},
 qa:function(){return '<rect width="60" height="30" fill="#8A1538"/><path d="M0 0h18l6 2.5-6 2.5 6 2.5-6 2.5 6 2.5-6 2.5 6 2.5-6 2.5 6 2.5-6 2.5H0Z" fill="#fff"/>';},
 bh:function(){return '<rect width="60" height="30" fill="#CE1126"/><path d="M0 0h16l7 3-7 3 7 3-7 3 7 3-7 3 7 3-7 3 7 3-7 3H0Z" fill="#fff"/>';},
 kw:function(){return bands('h',[['#007A3D',1],['#fff',1],['#CE1126',1]])+'<path d="M0 0 18 10v10L0 30Z" fill="#000"/>';},
 om:function(){return bands('h',[['#fff',1],['#DB161B',1],['#008000',1]])+'<rect width="16" height="30" fill="#DB161B"/><rect x="4" y="6" width="8" height="6" rx="1" fill="#fff" opacity=".85"/>';},
 jo:function(){return bands('h',[['#000',1],['#fff',1],['#007A3D',1]])+'<path d="M0 0 26 15 0 30Z" fill="#CE1126"/>'+star(9,15,2.4,'#fff');},
 iq:function(){return bands('h',[['#CE1126',1],['#fff',1],['#000',1]])+'<rect x="20" y="13" width="20" height="4" rx="2" fill="#007A3D"/>';},
 eg:function(){return bands('h',[['#CE1126',1],['#fff',1],['#000',1]])+disc(30,15,3.4,'#C09300');},
 za:function(){return '<rect width="60" height="30" fill="#002395"/><rect width="60" height="15" fill="#DE3831"/>'+
   '<path d="M0 0 22 15 0 30Z" fill="#007A4D"/><path d="M0 3 18 15 0 27Z" fill="#000"/>'+
   '<path d="M0 6 14 15 0 24Z" fill="#FFB612"/><path d="M20 12h40v6H20Z" fill="#007A4D"/><path d="M0 0 24 15 0 30" fill="none" stroke="#fff" stroke-width="4"/>';},
 ke:function(){return bands('h',[['#000',1],['#fff',.14],['#BB0000',1],['#fff',.14],['#006600',1]])+
   '<ellipse cx="30" cy="15" rx="4.5" ry="9" fill="#BB0000" stroke="#fff" stroke-width="1"/>';},
 ma:function(){return '<rect width="60" height="30" fill="#C1272D"/>'+star(30,15,8,'none').replace('fill="none"','fill="none" stroke="#006233" stroke-width="1.6"');},
 tn:function(){return '<rect width="60" height="30" fill="#E70013"/>'+disc(30,15,8,'#fff')+cres(30,15,5.6,'#E70013','#fff',1.9)+star(33,15,2.6,'#E70013');},
 ar:function(){return bands('h',[['#74ACDF',1],['#fff',1],['#74ACDF',1]])+disc(30,15,3.2,'#F6B40E');},
 cl:function(){return '<rect width="60" height="30" fill="#fff"/><rect y="15" width="60" height="15" fill="#D52B1E"/><rect width="20" height="15" fill="#0039A6"/>'+star(10,7.5,4,'#fff');},
 mx:function(){return bands('v',[['#006847',1],['#fff',1],['#CE1126',1]])+disc(30,15,3.6,'#8C6239');},
 al:function(){return '<rect width="60" height="30" fill="#E41E20"/><path d="M30 8l6 4-3 2 3 3-4 1 1 4-3-2-3 2 1-4-4-1 3-3-3-2Z" fill="#000"/>';},
 ba:function(){return '<rect width="60" height="30" fill="#002395"/><path d="M18 0h26L18 30Z" fill="#FECB00"/>'+
   '<path d="M6 2l22 26" stroke="#fff" stroke-width="2" stroke-dasharray="3 3"/>';},
 mk:function(){return '<rect width="60" height="30" fill="#D20000"/><path d="M30 15 0 0M30 15 60 0M30 15 0 30M30 15 60 30M30 15V0M30 15v15M30 15H0M30 15h60" stroke="#FFE600" stroke-width="3"/>'+disc(30,15,5,'#FFE600');},
 me:function(){return '<rect width="60" height="30" fill="#C40308"/><rect x="3" y="3" width="54" height="24" fill="none" stroke="#D4AF37" stroke-width="2"/>'+disc(30,15,4,'#D4AF37');},
 cy:function(){return '<rect width="60" height="30" fill="#fff"/><path d="M22 11q8-4 16 0-3 6-8 6t-8-6Z" fill="#D57800"/><path d="M24 22q6 3 12 0" stroke="#4E5B31" stroke-width="1.6" fill="none"/>';},
 ge:function(){return '<rect width="60" height="30" fill="#fff"/><rect x="26" width="8" height="30" fill="#FF0000"/><rect y="11" width="60" height="8" fill="#FF0000"/>'+
   '<g fill="#FF0000"><rect x="11" y="4" width="2.6" height="7"/><rect x="8.8" y="6.2" width="7" height="2.6"/><rect x="46" y="4" width="2.6" height="7"/><rect x="43.8" y="6.2" width="7" height="2.6"/><rect x="11" y="19" width="2.6" height="7"/><rect x="8.8" y="21.2" width="7" height="2.6"/><rect x="46" y="19" width="2.6" height="7"/><rect x="43.8" y="21.2" width="7" height="2.6"/></g>';},
 kz:function(){return '<rect width="60" height="30" fill="#00AFCA"/>'+disc(30,14,5,'#FEC50C')+'<path d="M20 24q10-5 20 0" stroke="#FEC50C" stroke-width="2" fill="none"/>';},
 uz:function(){return bands('h',[['#0099B5',1],['#fff',1],['#1EB53A',1]])+cres(12,5.5,3.4,'#fff','#0099B5',1.3)+
   '<rect y="9.6" width="60" height=".9" fill="#CE1126"/><rect y="19.5" width="60" height=".9" fill="#CE1126"/>';},
 az:function(){return bands('h',[['#00B5E2',1],['#EF3340',1],['#509E2F',1]])+cres(28,15,3.6,'#fff','#EF3340',1.3)+star(35,15,2,'#fff');},
 es:function(){return bands('h',[['#AA151B',1],['#F1BF00',2],['#AA151B',1]])+'<rect x="14" y="12" width="5" height="6" rx="1" fill="#AA151B"/>';},
 hr:function(){return bands('h',[['#FF0000',1],['#fff',1],['#171796',1]])+'<path d="M26 10h8v6l-4 4-4-4Z" fill="#D0021B"/>';},
 rs:function(){return bands('h',[['#C6363C',1],['#0C4076',1],['#fff',1]])+'<path d="M22 10h7v6l-3.5 3.5L22 16Z" fill="#EDB92E"/>';},
 sk:function(){return bands('h',[['#fff',1],['#0B4EA2',1],['#EE1C25',1]])+'<path d="M20 9h8v7l-4 4-4-4Z" fill="#EE1C25" stroke="#fff" stroke-width="1"/>';},
 si:function(){return bands('h',[['#fff',1],['#005DA4',1],['#ED1C24',1]])+'<path d="M22 8h7v6l-3.5 3.5L22 14Z" fill="#005DA4" stroke="#fff" stroke-width=".8"/>';},
 mt:function(){return bands('v',[['#fff',1],['#CF142B',1]])+'<rect x="4" y="4" width="4" height="4" fill="#9E9E9E"/>';},
 ng:function(){return bands('v',[['#008751',1],['#fff',1],['#008751',1]]);},
 co:function(){return bands('h',[['#FCD116',2],['#003893',1],['#CE1126',1]]);},
 se:function(){return nordic('#006AA7','#FECC02');},
 no:function(){return nordic('#BA0C2F','#fff','#00205B');},
 dk:function(){return nordic('#C8102E','#fff');},
 fi:function(){return nordic('#fff','#003580');},
 is:function(){return nordic('#02529C','#fff','#DC1E35');},
 /* Added 2026-11-13 (v12-fix2) — these 8 were showing the generic placeholder. */
 pk:function(){return '<rect width="60" height="30" fill="#01411C"/>'+
   '<rect width="15" height="30" fill="#fff"/>'+
   cres(38,15,7,'#fff','#01411C',2.4)+star(47,15,3,'#fff');},
 vn:function(){return '<rect width="60" height="30" fill="#DA251D"/>'+star(30,15,8,'#FFFF00');},
 pa:function(){return '<rect width="60" height="30" fill="#fff"/>'+
   '<rect x="30" width="30" height="15" fill="#D21034"/>'+
   '<rect y="15" width="30" height="15" fill="#005293"/>'+
   star(15,7.5,4,'#005293')+star(45,22.5,4,'#D21034');},
 tw:function(){var s='<rect width="60" height="30" fill="#FE0000"/><rect width="30" height="15" fill="#000095"/>'+disc(15,7.5,4,'#fff'),i,a;
   for(i=0;i<12;i++){a=i*Math.PI/6;s+='<line x1="15" y1="7.5" x2="'+(15+6*Math.cos(a)).toFixed(2)+'" y2="'+(7.5+6*Math.sin(a)).toFixed(2)+'" stroke="#fff" stroke-width="0.8"/>';}
   return s;},
 hk:function(){var s='<rect width="60" height="30" fill="#DE2910"/>',i,a;
   for(i=0;i<5;i++){a=-Math.PI/2+i*2*Math.PI/5;s+=disc(30+6*Math.cos(a),15+6*Math.sin(a),3,'#fff');}
   s+=disc(30,15,2.5,'#fff');return s;},
 kh:function(){return bands('h',[['#032EA1',1],['#E00025',2],['#032EA1',1]])+
   '<rect x="24" y="9" width="12" height="12" fill="#fff"/><rect x="26" y="6" width="8" height="4" fill="#fff"/><rect x="20" y="13" width="4" height="8" fill="#fff"/><rect x="36" y="13" width="4" height="8" fill="#fff"/>';},
 my:function(){var s='',i;for(i=0;i<14;i++)s+='<rect y="'+(i*30/14).toFixed(2)+'" width="60" height="'+(30/14).toFixed(2)+'" fill="'+(i%2?'#fff':'#CC0001')+'"/>';
   s+='<rect width="30" height="16" fill="#010066"/>'+cres(14,8,5,'#FFCC00','#010066',1.8)+star(22,8,3,'#FFCC00');return s;},
 uy:function(){var s='',i;for(i=0;i<9;i++)s+='<rect y="'+(i*30/9).toFixed(2)+'" width="60" height="'+(30/9).toFixed(2)+'" fill="'+(i%2?'#0038A8':'#fff')+'"/>';
   s+='<rect width="24" height="16.7" fill="#fff"/>'+disc(12,8.3,5,'#FCD116')+disc(12,8.3,2.5,'#fff');return s;}
};
var PLAIN={
 de:'h|#000000,#DD0000,#FFCE00', nl:'h|#AE1C28,#FFFFFF,#21468B', fr:'v|#0055A4,#FFFFFF,#EF4135',
 it:'v|#009246,#FFFFFF,#CE2B37', pl:'h|#FFFFFF,#DC143C', ru:'h|#FFFFFF,#0039A6,#D52B1E',
 ua:'h|#0057B7,#FFD700', ie:'v|#169B62,#FFFFFF,#FF883E', at:'h|#ED2939,#FFFFFF,#ED2939',
 be:'v|#000000,#FDDA24,#EF3340', ro:'v|#002B7F,#FCD116,#CE1126', hu:'h|#CE2939,#FFFFFF,#477050',
 bg:'h|#FFFFFF,#00966E,#D62612', ee:'h|#0072CE,#000000,#FFFFFF', lt:'h|#FDB913,#006A44,#C1272D',
 lu:'h|#ED2939,#FFFFFF,#00A1DE', md:'v|#0046AE,#FFD200,#CC092F', lv:'h|#9E3039,#FFFFFF,#9E3039',
 am:'h|#D90012,#0033A0,#F2A800',
 /* Added 2026-11-13 (v12-fix2) — these 7 were showing the generic placeholder. */
 bo:'h|#D52B1E,#FCD116,#007934', ec:'h|#FFDD00:2,#034EA2,#ED1C24', gt:'v|#4997D0,#FFFFFF,#4997D0',
 id:'h|#FF0000,#FFFFFF', pe:'v|#D91023,#FFFFFF,#D91023', py:'h|#D52B1E,#FFFFFF,#0038A8',
 th:'h|#A51931,#FFFFFF,#2D2A4A:2,#FFFFFF,#A51931'
};
/* Drawn SVG flag for a country code. If the country is not in the
 * tables (a NEW country the app has never seen), fall back to the
 * real flag emoji that came with the server's name — so a new
 * country always shows its flag, never a generic placeholder. If
 * even that is missing, show the country code letters. */
function flag(cc,emoji){
  cc=(cc||'').toLowerCase();
  var inner;
  if(SPECIAL[cc])inner=SPECIAL[cc]();
  else if(PLAIN[cc]){var p=PLAIN[cc].split('|'),cols=p[1].split(','),list=[],i;
    for(i=0;i<cols.length;i++){var seg=cols[i].split(':');list.push([seg[0],seg.length>1?parseFloat(seg[1]):1]);}
    inner=bands(p[0]==='v'?'v':'h',list);}
  else if(emoji){
    return '<svg viewBox="0 0 60 30" aria-hidden="true"><text x="30" y="22" font-size="22" text-anchor="middle">'+escHtml(emoji)+'</text></svg>';
  }
  else inner='<rect width="60" height="30" fill="#1a2338"/><text x="30" y="20" font-size="13" font-weight="700" text-anchor="middle" fill="#e9eefb" font-family="sans-serif">'+escHtml(cc.toUpperCase())+'</text>';
  return '<svg viewBox="0 0 60 30" aria-hidden="true">'+inner+'</svg>';
}

/* ===== real server store (from the subscription, not demo data) ===== */
var S2={servers:[],pings:{},groups:[],sel:null,fastestSel:true,expandedFlag:null};
function cur(){return S2;}
function pingKeyFor(s){return s.ip+':'+(s.port||'');}
function pingKey(ip,port){return ip+':'+(port||'');}
var byKey={};
var groupByCc={};   /* country code -> group (O(1) lookup for paintPings) */
var elByCc={};      /* country code -> {best, sig} elements (cached in renderList) */
var elByPing={};    /* ping key -> server-row element (cached in renderList) */
var rtt={};           /* ip -> live through-tunnel RTT while connected */
var testing=false;
var connectInFlight=false, connectInFlightAt=0;
var connectedIp=null, pendingSelIp=null;
var state='idle', liveIp=null, pendingReconnect=false, lastActualCode=null, lastIpCheckAt=0;
var prevBytes=-1, lastBytesChange=0, stallReported=false;
var pendingServer=null, pendingFastest=false;
var openCC=null, listOpen=false;

function escHtml(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
/* Emoji flag (from the server list) -> 2-letter country code, for the SVG flags. */
function flagToCode(fl){
  try{
    var cps=Array.from(fl||'');
    if(cps.length<2) return '';
    var a=cps[0].codePointAt(0)-0x1F1E6, b=cps[1].codePointAt(0)-0x1F1E6;
    if(a<0||a>25||b<0||b>25) return '';
    return String.fromCharCode(65+a,65+b);
  }catch(e){ return ''; }
}
var regionNames=null;
function countryName(code){
  if(!code) return '';
  try{
    if(!regionNames&&typeof Intl!=='undefined'&&Intl.DisplayNames){
      regionNames=new Intl.DisplayNames(['en'],{type:'region'});
    }
    if(regionNames){ var n=regionNames.of(code); if(n&&n!==code) return n; }
  }catch(e){}
  return code;
}
/* ===== country identity — from the flag ON the server name, and
 * nothing else. When a list arrives, each server is stamped ONCE
 * with its country code + name, derived from its own flag emoji.
 * The country list groups by that stamped code, so a server can
 * only ever appear under the country its own flag names. No IP,
 * no region code and no GeoIP is ever consulted for the country. */
function stampCountry(s){
  var cc=flagToCode(s.flag||'');
  s.cc=cc;
  s.country=cc?countryName(cc):'Unknown';
  s.flagSvg=flag(cc.toLowerCase(),s.flag||'');
  return s;
}
/* Ping text/class — the OLD app's thresholds (good <200, mid <400),
 * which fit real 4G pings (the design's 80/150 turned every real
 * server red). TIMEOUT (-1) is BAD/red, never green: -1 < 80 used to
 * make it lvl 3 (green) — that was the inverted-antenna bug. */
function pingTextFor(ms){
  if(ms==null) return '—';
  if(ms<0) return 'TIMEOUT';
  return ms+' ms';
}
function pingClassFor(ms){
  if(ms==null) return '';
  if(ms<0) return 'bad';
  if(ms<200) return 'good';
  if(ms<400) return 'mid';
  return 'bad';
}
/* Signal bars: 0 = untested (no bars), 1 = red (TIMEOUT or >=400),
 * 2 = amber (200-399), 3 = green (<200). */
function lvl(ms){
  if(ms==null) return '0';
  if(ms<0) return '1';
  if(ms<200) return '3';
  if(ms<400) return '2';
  return '1';
}
/* DISPLAY ping — live through-tunnel RTT while connected, else the SsProbe value. */
function displayMs(s){
  if(connectedIp===s.ip&&rtt[s.ip]!=null) return rtt[s.ip];
  return cur().pings[pingKeyFor(s)];
}
function bestPingOf(g){
  var best=-1, tested=false;
  g.servers.forEach(function(s){
    var ms=displayMs(s);
    if(ms!=null){ tested=true; if(ms>=0&&(best<0||ms<best)) best=ms; }
  });
  return tested?best:null;
}
function bestPingAll(){
  var best=-1, tested=false;
  cur().servers.forEach(function(s){
    var ms=displayMs(s);
    if(ms!=null){ tested=true; if(ms>=0&&(best<0||ms<best)) best=ms; }
  });
  return tested?best:null;
}
function bestServerOf(g){
  var best=null, bestMs=Infinity;
  var pings=cur().pings;
  g.servers.forEach(function(s){
    var ms=pings[pingKeyFor(s)];
    if(ms!=null&&ms>=0&&ms<bestMs){ bestMs=ms; best=s; }
  });
  return best||g.servers[0]||null;
}
function serverByIp(ip){
  var arr=cur().servers;
  for(var i=0;i<arr.length;i++){ if(arr[i].ip===ip) return arr[i]; }
  return null;
}
/* Country list, rebuilt from scratch: group by the stamped country
 * code — which came only from the flag emoji on the server's own
 * name. A server is in a country's group if and only if its flag
 * says that country. */
function groupServers(){
  var st=cur();
  var map={}, order=[];
  st.servers.forEach(function(s){
    var key=s.cc||'';
    if(!map[key]){
      map[key]={cc:key,name:s.country||'Unknown',flagSvg:s.flagSvg,servers:[]};
      order.push(key);
    }
    map[key].servers.push(s);
  });
  st.groups=order.map(function(k){ return map[k]; });
  /* O(1) group lookup by country code (paintPings used to scan all groups per row). */
  groupByCc={};
  st.groups.forEach(function(g){ groupByCc[g.cc]=g; });
  st.groups.sort(function(a,b){
    if(b.servers.length!==a.servers.length) return b.servers.length-a.servers.length;
    return a.name<b.name?-1:a.name>b.name?1:0;
  });
}
/* Countries with the better ping come first (measured asc, then all-TIMEOUT, then untested). */
function sortGroups(){
  cur().groups.sort(function(a,b){
    var pa=bestPingOf(a), pb=bestPingOf(b);
    var ra=pa==null?2:(pa<0?1:0);
    var rb=pb==null?2:(pb<0?1:0);
    if(ra!==rb) return ra-rb;
    if(ra===0&&pa!==pb) return pa-pb;
    if(b.servers.length!==a.servers.length) return b.servers.length-a.servers.length;
    return a.name<b.name?-1:a.name>b.name?1:0;
  });
}
function applyServerList(arr){
  if(!Array.isArray(arr)) arr=[];
  var st=cur();
  st.servers=arr;
  byKey={};
  arr.forEach(function(s){ stampCountry(s); byKey[pingKeyFor(s)]=s; });
  if(st.sel&&!st.fastestSel){
    var still=null;
    for(var i=0;i<arr.length;i++){ if(arr[i].ip===st.sel.ip){ still=arr[i]; break; } }
    if(still){ st.sel=still; } else { st.sel=null; st.fastestSel=true; }
  }
  if(pendingSelIp){
    var found=null;
    for(var j=0;j<arr.length;j++){ if(arr[j].ip===pendingSelIp){ found=arr[j]; break; } }
    pendingSelIp=null;
    if(found){ st.sel=found; st.fastestSel=false; }
    else { st.fastestSel=true; st.sel=null; }
  }
  groupServers();
  renderList(); paintTrigger();
  var el=$('srvStatus');
  if(el) el.textContent=arr.length?arr.length+' servers · '+st.groups.length+' countries':'no servers';
  startPingTest();
}
/* ===== real ping flow (Java SsProbe sweep) ===== */
function applyPing(ip,ms,port){
  var k=pingKey(ip,port);
  cur().pings[k]=ms;
  paintPings();
}
function applyLiveRtt(ip,ms){
  rtt[ip]=ms;
  paintPings();
}
function clearLiveRtt(){
  if(Object.keys(rtt).length===0) return;
  rtt={};
  renderList(); paintTrigger();
}
/* ===== ping painting — coalesced per animation frame =====
 * The sweep delivers 171 pings in a burst. Painting the DOM on
 * every single one (two querySelectorAll scans + a group scan per
 * row, 171 times) saturated the main thread and made every tap
 * feel laggy — on the main screen AND in the hamburger menu. So
 * pings only SCHEDULE a paint; one paint per frame does all the
 * DOM work, using the element cache built by renderList. */
var paintPending=false, paintRaf=0;
function paintPings(){
  if(paintPending) return;
  paintPending=true;
  paintRaf=requestAnimationFrame(function(){ paintPending=false; paintRaf=0; paintPingsNow(); });
}
function flushPings(){
  if(!paintPending) return;
  try{ cancelAnimationFrame(paintRaf); }catch(e){}
  paintPending=false; paintRaf=0;
  paintPingsNow();
}
function paintPingsNow(){
  /* Server rows (only the expanded country's rows exist in the DOM). */
  var n=document.querySelectorAll('[data-ping]'),i,s,sv;
  for(i=0;i<n.length;i++){
    s=n[i].getAttribute('data-ping'); sv=byKey[s];
    var ms=sv?displayMs(sv):null;
    n[i].textContent=pingTextFor(ms);
    n[i].className='ms mono '+pingClassFor(ms);
  }
  /* Country bests + signal bars, via the cached elements (no DOM scan, no group scan). */
  var groups=cur().groups;
  for(i=0;i<groups.length;i++){
    var g=groups[i], v=bestPingOf(g), els=elByCc[g.cc];
    if(!els) continue;
    if(els.best){ els.best.textContent=pingTextFor(v); els.best.className='ms mono '+pingClassFor(v); }
    if(els.sig){ els.sig.setAttribute('data-lvl',lvl(v)); }
  }
  var f=bestPingAll(), fl2=document.querySelector('[data-fastest]');
  if(fl2){ fl2.textContent=pingTextFor(f); fl2.className='ms mono '+pingClassFor(f); }
  if(cur().fastestSel) paintTrigger();
}
function startPingTest(){
  testing=true;
  renderList();
  try{ AndroidBridge.pingServers(); }catch(e){}
}
window.onServers=function(jsonStr){
  try{
    var arr=JSON.parse(jsonStr);
    applyServerList(arr);
  }catch(e){
    var st2=$('srvStatus');
    if(st2) st2.textContent='Could not load servers.';
  }
};
window.onServersError=function(msg){
  /* Both subscriptions yielded nothing — the cached list (if any) stays; never empty it here. */
  if(!cur().servers.length){
    var st=$('srvStatus');
    if(st) st.textContent=msg||'Could not load servers.';
  }
};
window.onServerPing=function(jsonStr){
  try{
    var e=JSON.parse(jsonStr);
    if(e&&e.ip!=null) applyPing(e.ip,e.pingMs,e.port);
  }catch(err){}
};
window.onServerPingDone=function(){
  testing=false;
  var rb=$('refreshBtn'); if(rb) rb.classList.remove('spin');
  flushPings(); /* paint any pings still queued, before the re-sort */
  renderList(); paintTrigger();
};

/* ===== server list (the new design, real data) ===== */
var BOLT='<span class="bolt"><svg viewBox="0 0 24 24" fill="currentColor"><path d="M13 2 4.5 13H11l-1 9 8.5-11H12l1-9Z"/></svg></span>';
function paintTrigger(){
  var st=cur();
  var s=st.fastestSel?null:st.sel;
  if(!s){
    var best=null, bestMs=Infinity;
    st.servers.forEach(function(x){
      var ms=displayMs(x);
      if(ms!=null&&ms>=0&&ms<bestMs){ bestMs=ms; best=x; }
    });
    $('trigFlag').innerHTML=BOLT;
    $('trigName').textContent='Fastest — Auto';
    $('trigMeta').textContent=best?((best.proto==='vmess'&&best.name?best.name:best.country)+' · '+bestMs+' ms'):'Selecting best node…';
  }else{
    $('trigFlag').innerHTML=s.flagSvg||flag((s.cc||'').toLowerCase());
    $('trigName').textContent=(s.proto==='vmess'&&s.name)?s.name:(s.country||s.ip);
    var ms2=displayMs(s);
    $('trigMeta').textContent=s.ip+' · '+pingTextFor(ms2);
  }
}
function renderList(){
  var box=$('countryList');
  if(!box) return;
  sortGroups();
  var arr=cur().groups;
  var f=bestPingAll();
  var h='<button type="button" class="crow'+(cur().fastestSel?' sel':'')+'" data-auto="1">'+
    '<span class="fl">'+BOLT+'</span>'+
    '<span class="nm"><b>Fastest — Auto</b><em>Lowest latency node</em></span>'+
    '<span class="ms mono '+pingClassFor(f)+'" data-fastest="1">'+pingTextFor(f)+'</span></button>';
  arr.forEach(function(g){
    var v=bestPingOf(g), op=openCC===g.cc;
    h+='<div class="cgroup'+(op?' open':'')+'">'+
      '<button type="button" class="crow'+(cur().sel&&cur().sel.cc===g.cc?' sel':'')+'" data-cc="'+escHtml(g.cc)+'">'+
      '<span class="fl">'+g.flagSvg+'</span>'+
      '<span class="nm"><b>'+escHtml(g.name)+'</b><em>'+g.servers.length+' server'+(g.servers.length>1?'s':'')+'</em></span>'+
      '<span class="sig" data-sig="'+escHtml(g.cc)+'" data-lvl="'+lvl(v)+'"><i></i><i></i><i></i></span>'+
      '<span class="ms mono '+pingClassFor(v)+'" data-best="'+escHtml(g.cc)+'">'+pingTextFor(v)+'</span>'+
      '<svg class="cv" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="m6 9 6 6 6-6"/></svg></button>';
    if(op){
      h+='<div class="sub">';
      g.servers.forEach(function(s){
        var ms=displayMs(s);
        var label=(s.proto==='vmess'&&s.name)?s.name:s.ip;
        var sub=(s.proto==='vmess'&&s.name)?s.ip:'Shadowsocks';
        h+='<button type="button" class="srow'+(cur().sel&&cur().sel.ip===s.ip?' sel':'')+'" data-sid="'+escHtml(s.ip)+'" data-port="'+(s.port||'')+'">'+
          '<span class="dot"></span><span class="nm"><b>'+escHtml(label)+'</b><em class="mono">'+escHtml(sub)+'</em></span>'+
          '<span class="ms mono '+pingClassFor(ms)+'" data-ping="'+escHtml(pingKeyFor(s))+'">'+pingTextFor(ms)+'</span></button>';
      });
      h+='</div>';
    }
    h+='</div>';
  });
  box.innerHTML=h;
  /* Cache the elements paintPingsNow updates, so a ping never has to
   * scan the DOM: cc -> {best, sig}. (Server rows are rebuilt here
   * too, so the [data-ping] cache is refreshed in paintPingsNow by
   * query — those rows only exist for the one expanded country.) */
  elByCc={};
  var bestEls=box.querySelectorAll('[data-best]');
  for(var bi=0;bi<bestEls.length;bi++){
    var cc=bestEls[bi].getAttribute('data-best');
    if(cc) elByCc[cc]={best:bestEls[bi],sig:box.querySelector('[data-sig="'+cc+'"]')};
  }
}
function toggleList(v){
  listOpen=(v===undefined)?!listOpen:v;
  $('srvCard').classList.toggle('open',listOpen);
  $('dropdownBtn').setAttribute('aria-expanded',String(listOpen));
  if(listOpen) renderList();
}
/* Tapping a server connects to it immediately, in every state. */
function chooseServer(s){
  var st=cur();
  var changed=!st.sel||st.sel.ip!==s.ip||(st.sel.proto||'ss')!==(s.proto||'ss');
  st.sel=s; st.fastestSel=false; renderList(); paintTrigger(); toggleList(false);
  if(!changed){ if(state==='idle') connect(); return; }
  try{ AndroidBridge.saveLastSelection('server:'+s.ip); }catch(e){}
  if(state==='on'){ pendingServer=s; pendingReconnect=true; disconnect(); return; }
  if(state==='busy'){
    try{ AndroidBridge.cancelConnect(); }catch(e){}
    setState('idle');
  }
  connect();
}
function chooseFastest(){
  var st=cur();
  var changed=!st.fastestSel;
  st.fastestSel=true; st.sel=null; renderList(); paintTrigger(); toggleList(false);
  if(!changed){ if(state==='idle') connect(); return; }
  try{ AndroidBridge.saveLastSelection('fastest'); }catch(e){}
  if(state==='on'){ pendingFastest=true; pendingReconnect=true; disconnect(); return; }
  if(state==='busy'){
    try{ AndroidBridge.cancelConnect(); }catch(e){}
    setState('idle');
  }
  connect();
}
/* Tapping a country row (not the chevron) connects to its best server. */
function chooseCountry(g){
  var best=bestServerOf(g);
  if(!best) return;
  var wasOn=(state==='on');
  chooseServer(best);
  if(!wasOn&&state==='idle'){ connect(); }
}

/* ===== connect ===== */
var raf=null, C=2*Math.PI*54, progTimer=null;
function setProg(p){
  var el=$('ringFg'); if(el) el.style.strokeDashoffset=(C*(1-p)).toFixed(2);
  var pc=$('connPct'); if(pc) pc.textContent=Math.round(p*100)+'%';
}
function paintConn(){
  var on=state==='on', busy=state==='busy';
  $('connectBtn').className='connect'+(on?' on':'')+(busy?' busy':'');
  $('stateChip').className='chip'+(on?' live':busy?' busy':'');
  $('stateTxt').textContent=on?'Secured':busy?'Connecting':'Disconnected';
  $('modeChip').textContent=curMode==='proxy'?'Proxy Mode':'VPN';
  $('modeChip').className='chip mode'+(curMode==='proxy'?' px':'');
  $('connLabel').textContent=on?'Disconnect':busy?'Connecting':'Connect';
  $('connSub').textContent=on?(curMode==='proxy'?'Local proxy running':'Tunnel active'):busy?'Please wait':'Tap to connect';
  $('connPct').style.display=busy?'block':'none';
  $('connIcon').innerHTML=on
    ?'<path d="M12 3 4.5 6v6c0 4.6 3.2 8.4 7.5 9.5 4.3-1.1 7.5-4.9 7.5-9.5V6L12 3Z"/><path d="m9 12 2.2 2.2L15.5 10"/>'
    :'<path d="M12 3v9"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/>';
  var sv=connectedIp?serverByIp(connectedIp):null;
  if(on&&sv){
    $('ipVal').textContent=liveIp||sv.ip;
    /* The country shown is the connected server's own country —
     * stamped from the flag on that server's name — so the flag,
     * the country and the server always agree. */
    $('ipMeta').innerHTML='<span class="fl xs">'+(sv.flagSvg||'')+'</span><span>'+escHtml(sv.country||sv.ip)+' · '+escHtml(sv.ip)+'</span>';
    $('ipCard').classList.add('live');
  }else{
    $('ipVal').textContent='—.—.—.—';
    $('ipMeta').textContent=busy?'Establishing tunnel…':'Not connected';
    $('ipCard').classList.remove('live');
  }
}
function setState(k){
  state=k;
  if(k!=='busy'){
    if(progTimer){ clearInterval(progTimer); progTimer=null; }
    if(raf){ cancelAnimationFrame(raf); raf=null; }
    setProg(k==='on'?1:0);
  }
  paintConn();
}
function showIp(ip){
  if(state!=='on') return;
  var sv=connectedIp?serverByIp(connectedIp):null;
  if(sv){
    if(ip&&ip!=='—'&&ip!=='?'){ liveIp=ip; }
    paintConn();
    return;
  }
  if(!ip||ip==='—'||ip==='?') return;
  liveIp=ip;
  paintConn();
}
function connect(){
  if(state==='busy') return;
  setState('busy');
  connectInFlight=true; connectInFlightAt=Date.now();
  /* Ring progress is a visual while the real connect runs; it completes when the poll confirms the tunnel. */
  var p=0;
  if(progTimer) clearInterval(progTimer);
  progTimer=setInterval(function(){
    p=Math.min(0.9,p+0.06);
    setProg(p);
  },120);
  try{
    if(cur().fastestSel||!cur().sel){ AndroidBridge.connectFastest(); }
    else { AndroidBridge.connectServer(cur().sel.ip, cur().sel.flag, cur().sel.proto||'ss'); }
  }catch(e){ connectInFlight=false; setState('idle'); }
}
function disconnect(){
  if(state!=='on'&&state!=='busy') return;
  connectInFlight=false;
  setState('idle'); liveIp=null; lastActualCode=null;
  clearLiveRtt();
  try{ AndroidBridge.disconnect(); }catch(e){}
}
window.onExitIp=function(ip){ showIp(ip); };
window.onConnectError=function(msg){
  pendingReconnect=false; pendingServer=null; pendingFastest=false;
  testing=false;
  connectInFlight=false;
  var rb=$('refreshBtn'); if(rb) rb.classList.remove('spin');
  setState('idle');
};
/* ===== poll loop (Java is the source of truth for connected state) ===== */
function poll(){
  try{
    if(typeof AndroidBridge==='undefined') return;
    var s=JSON.parse(AndroidBridge.getStatusJson());
    var newIp=s.serverIp||null;
    if(newIp!==connectedIp){
      connectedIp=newIp;
      clearLiveRtt();
    }
    if(s.serverTesting&&!testing){ testing=true; renderList(); }
    if(s.mode&&s.mode!==curMode){ curMode=s.mode; paintMode(); paintConn(); }
    if(connectInFlight&&Date.now()-connectInFlightAt>30000) connectInFlight=false;
    if(s.connected){
      connectInFlight=false;
      if(state!=='on') setState('on');
      showIp(s.ip);
      if((!s.ip||s.ip==='—'||s.ip==='?')&&Date.now()-lastIpCheckAt>10000){
        lastIpCheckAt=Date.now();
        try{ AndroidBridge.checkExitIp(); }catch(e){}
      }
      if(s.sbPingMs>0&&connectedIp){
        if(rtt[connectedIp]!==s.sbPingMs) applyLiveRtt(connectedIp,s.sbPingMs);
      }
      var total=(s.txBytes||0)+(s.rxBytes||0);
      var now=Date.now();
      if(prevBytes<0){ prevBytes=total; lastBytesChange=now; }
      else if(total!==prevBytes){ prevBytes=total; lastBytesChange=now; stallReported=false; }
      else if(!stallReported&&s.statsLive&&s.connectedAt>0&&now-s.connectedAt>60000&&now-lastBytesChange>30000){
        stallReported=true;
        try{ AndroidBridge.optimize(); }catch(e){}
      }
      try{ AndroidBridge.pollNetworkChange(); }catch(e){}
    } else if(s.state==='connecting'||connectInFlight){
      if(state!=='busy') setState('busy');
    } else {
      if(state!=='idle') setState('idle');
      clearLiveRtt();
      if(pendingReconnect){ pendingReconnect=false; pendingServer=null; pendingFastest=false; window.setTimeout(connect,800); }
    }
  }catch(e){}
}

/* ===== settings panel (hamburger menu) ===== */
var curMode='vpn', selMode='vpn', splitMode='off', splitLastMode='bypass', splitPkgs=[], allApps=null, splitLoaded=false, iconCache={};
var tT=null;
function toast(m){var t=$('toast');if(!t)return;t.textContent=m;t.classList.add('show');clearTimeout(tT);
  tT=setTimeout(function(){t.classList.remove('show');},2400);}
function applyTheme(t,announce){
  root.setAttribute('data-theme',t);store.set('proxyarc-theme',t);
  var ts=$('themeSeg'); if(ts) ts.setAttribute('data-active',t);
  var td=$('themeDark'); if(td) td.classList.toggle('on',t==='dark');
  var tl=$('themeLight'); if(tl) tl.classList.toggle('on',t==='light');
  if(announce) toast(t==='dark'?'Dark theme':'Light theme');
}
/* Tapping a mode row only SELECTS the section — the active mode changes only via the proxy switch. */
function paintMode(){
  var px=curMode==='proxy';
  $('modeSeg').setAttribute('data-active',selMode);
  $('modeVpn').classList.toggle('on',selMode==='vpn');
  $('modeProxy').classList.toggle('on',selMode==='proxy');
  $('modeVpn').setAttribute('aria-selected',String(selMode==='vpn'));
  $('modeProxy').setAttribute('aria-selected',String(selMode==='proxy'));
  $('proxySwitchRow').classList.toggle('show',selMode==='proxy');
  $('proxySwitch').classList.toggle('on',px);
  $('proxySwitch').setAttribute('aria-checked',String(px));
  $('proxyInfo').classList.toggle('show',selMode==='proxy'&&px);
  $('modeHint').textContent=selMode==='proxy'
    ?(px?'Proxy mode is on — a local proxy runs on the phone.'
        :'Turn on the Proxy Mode switch to run a local proxy instead of the VPN.')
    :'All device traffic goes through the VPN tunnel.';
  /* Split tunneling lives only in the VPN section. */
  $('splitSec').style.display=selMode==='proxy'?'none':'';
  paintConn();
}
function paintSplit(){
  var n=splitPkgs.length;
  var on=splitMode!=='off';
  $('splitSwitch').classList.toggle('on',on);
  $('splitSwitch').setAttribute('aria-checked',String(on));
  $('splitControls').classList.toggle('show',on);
  $('splitSummary').innerHTML=splitMode==='off'?'Off':
    '<b>'+(splitMode==='bypass'?'Bypass':'Only')+'</b> · '+
    (n?(n+' app'+(n>1?'s':'')+' selected'):'No apps selected');
  var b1=$('segBypass'), b2=$('segOnly');
  if(b1) b1.classList.toggle('on',splitMode==='bypass');
  if(b2) b2.classList.toggle('on',splitMode==='only');
  var ss=$('splitSeg'); if(ss) ss.setAttribute('data-active',splitMode==='only'?'only':'bypass');
}
var settingsFirstLoad=true;
function loadSettings(){
  try{
    var s=JSON.parse(AndroidBridge.getSettings());
    curMode=s.mode==='proxy'?'proxy':'vpn';
    splitMode=s.splitMode||'off';
    splitLastMode=s.splitLastMode==='only'?'only':'bypass';
    splitPkgs=s.splitPackages||[];
    /* If the saved settings somehow have BOTH proxy and split ON, keep split and turn proxy OFF. */
    if(curMode==='proxy'&&splitMode!=='off'){
      curMode='vpn';
      try{ AndroidBridge.setMode('vpn'); }catch(e){}
    }
    if(settingsFirstLoad){
      settingsFirstLoad=false;
      selMode=curMode;
      var ls=s.lastSelection||'fastest';
      if(ls&&ls!=='fastest'&&ls.indexOf('server:')===0){
        pendingSelIp=ls.substring('server:'.length);
      }
    }
  }catch(e){}
  paintMode(); paintSplit();
}
var panelOpen=false, lastFocus=null;
function setPanel(v){
  panelOpen=v;
  document.body.classList.toggle('panel-open',v);
  var bb=$('burgerBtn'); if(bb) bb.setAttribute('aria-expanded',String(v));
  if(v){ loadSettings(); if(lastFocus===null) lastFocus=document.activeElement; }
  if(!v){ closeApps(); }
  syncOverlayDepth();
}
function syncOverlayDepth(){
  var d=0;
  if(panelOpen) d++;
  if(appsOpen) d++;
  try{ AndroidBridge.setOverlayDepth(d); }catch(e){}
}
window.__closeTopOverlay=function(){
  if(appsOpen){ closeApps(); }
  else if(panelOpen){ setPanel(false); }
  syncOverlayDepth();
};
/* The proxy switch is the ONLY way to change the active mode. */
function onProxySwitch(){
  var m=curMode==='proxy'?'vpn':'proxy';
  if(m==='proxy'&&splitMode!=='off'){
    splitLastMode=splitMode;
    splitMode='off';
    try{ AndroidBridge.saveSplitTunnel('off',JSON.stringify(splitPkgs)); }catch(e){}
    paintSplit();
  }
  if(state==='on'){ disconnect(); }
  curMode=m;
  try{ AndroidBridge.setMode(m); }catch(e){}
  paintMode();
}
function onSplitSwitch(){
  if(splitMode==='off'){
    if(curMode==='proxy'){
      if(state==='on'){ disconnect(); }
      curMode='vpn';
      try{ AndroidBridge.setMode('vpn'); }catch(e){}
    }
    splitMode=splitLastMode||'bypass';
  } else {
    splitLastMode=splitMode;
    splitMode='off';
  }
  try{ AndroidBridge.saveSplitTunnel(splitMode,JSON.stringify(splitPkgs)); }catch(e){}
  paintMode(); paintSplit();
}

/* ===== app picker (real installed apps) ===== */
var appsOpen=false, draft={}, draftMode='bypass';
function setInert(v){
  var n=$('panel').querySelectorAll('.p-head,.p-body,.p-foot'),i;
  for(i=0;i<n.length;i++){ try{ n[i].inert=v; }catch(e){} }
}
function appColor(pkg){
  var h=0;
  for(var i=0;i<pkg.length;i++){ h=(h*31+pkg.charCodeAt(i))>>>0; }
  var hues=[217,199,160,262,145,24,348,187,262,8];
  return 'hsl('+hues[h%hues.length]+' 70% 52%)';
}
function openApps(){
  if(appsOpen) return;
  if(!splitLoaded){
    splitLoaded=true;
    try{ allApps=JSON.parse(AndroidBridge.getInstalledApps()); }catch(e){ allApps=[]; }
  }
  draft={}; splitPkgs.forEach(function(p){ draft[p]=true; });
  draftMode=splitMode==='only'?'only':'bypass';
  appsOpen=true;
  $('appSearch').value='';
  renderApps('');
  $('panel').classList.add('apps-open'); setInert(true);
  syncOverlayDepth();
  setTimeout(function(){ $('appBack').focus(); },320);
}
function closeApps(){
  if(!appsOpen) return;
  appsOpen=false;
  $('panel').classList.remove('apps-open'); setInert(false);
  syncOverlayDepth();
}
function draftCount(){ var n=0; for(var k in draft) if(draft[k]) n++; return n; }
function renderApps(q){
  q=(q||'').toLowerCase();
  $('segBypass').classList.toggle('on',draftMode==='bypass');
  $('segOnly').classList.toggle('on',draftMode==='only');
  $('splitSeg').setAttribute('data-active',draftMode);
  $('appHint').textContent=draftMode==='bypass'
    ?'Selected apps skip the VPN. Everything else is tunnelled.'
    :'Only selected apps use the VPN. Everything else goes direct.';
  var rows=[];
  (allApps||[]).forEach(function(a){
    var label=a.label||a.name||'', pkg=a.package||a.pkg||'';
    if(!pkg) return;
    if(q&&label.toLowerCase().indexOf(q)<0&&pkg.toLowerCase().indexOf(q)<0) return;
    rows.push({label:label,pkg:pkg});
  });
  rows.sort(function(a,b){
    var as=draft[a.pkg]?0:1, bs=draft[b.pkg]?0:1;
    if(as!==bs) return as-bs;
    var al=a.label.toLowerCase(), bl=b.label.toLowerCase();
    return al<bl?-1:al>bl?1:0;
  });
  var h='', n=0;
  rows.forEach(function(a){
    n++; var on=!!draft[a.pkg];
    var ch=(a.label||'').charAt(0);
    var ph=/[\p{L}\p{N}]/u.test(ch)?escHtml(ch):'';
    h+='<button type="button" class="arow'+(on?' on':'')+'" data-pkg="'+escHtml(a.pkg)+'">'+
      '<span class="ai" style="--c:'+appColor(a.pkg)+'">'+ph+'</span>'+
      '<span class="an"><b>'+escHtml(a.label)+'</b><em class="mono">'+escHtml(a.pkg)+'</em></span>'+
      '<span class="ck"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="m5 13 4 4L19 7"/></svg></span></button>';
  });
  $('appList').innerHTML=n?h:'<div class="empty">No apps found.</div>';
  $('appCountLbl').textContent=draftCount()+' selected';
  $('appSaveLbl').textContent='Save'+(draftCount()?' · '+draftCount():'');
  loadAppIcons();
}
function loadAppIcons(){
  var rows=document.querySelectorAll('#appList .arow'), i=0;
  function tick(){
    var n=0;
    while(i<rows.length&&n<6){
      var row=rows[i++]; n++;
      var pkg=row.getAttribute('data-pkg');
      if(!pkg) continue;
      var tile=row.querySelector('.ai');
      if(!tile||tile.getAttribute('data-icon-done')) continue;
      tile.setAttribute('data-icon-done','1');
      if(iconCache[pkg]){ tile.innerHTML='<img alt="" src="'+iconCache[pkg]+'">'; continue; }
      try{
        var uri=AndroidBridge.getAppIcon(pkg);
        if(uri){ iconCache[pkg]=uri; tile.innerHTML='<img alt="" src="'+uri+'">'; }
      }catch(e){}
    }
    if(i<rows.length) window.setTimeout(tick,30);
  }
  tick();
}
function saveApps(){
  var pkgs=[];
  for(var k in draft) if(draft[k]) pkgs.push(k);
  splitPkgs=pkgs;
  if(splitMode!=='off') splitLastMode=splitMode;
  if(splitMode!=='off'&&curMode==='proxy'){
    if(state==='on'){ disconnect(); }
    curMode='vpn';
    try{ AndroidBridge.setMode('vpn'); }catch(e){}
  }
  try{ AndroidBridge.saveSplitTunnel(splitMode,JSON.stringify(pkgs)); }catch(e){}
  paintMode(); paintSplit();
  closeApps();
  toast('Split tunneling saved · '+splitPkgs.length+' apps');
}

/* ===== wiring ===== */
function on(id,ev,fn){var e=$(id);if(e)e.addEventListener(ev,fn);}
on('burgerBtn','click',function(e){e.stopPropagation();setPanel(!panelOpen);});
on('panelClose','click',function(){setPanel(false);});
on('scrim','click',function(){setPanel(false);});
on('modeVpn','click',function(){selMode='vpn';paintMode();});
on('modeProxy','click',function(){selMode='proxy';paintMode();});
on('proxySwitch','click',onProxySwitch);
on('splitSwitch','click',onSplitSwitch);
on('splitOpenBtn','click',openApps);
on('appBack','click',closeApps);
on('appSave','click',saveApps);
on('segBypass','click',function(){draftMode='bypass';renderApps($('appSearch').value);});
on('segOnly','click',function(){draftMode='only';renderApps($('appSearch').value);});
on('appSearch','input',function(){renderApps(this.value);});
on('appList','click',function(e){
  var b=e.target.closest?e.target.closest('.arow'):null;if(!b)return;
  var p=b.getAttribute('data-pkg');draft[p]=!draft[p];renderApps($('appSearch').value);});
on('themeBtn','click',function(){
  applyTheme(root.getAttribute('data-theme')==='dark'?'light':'dark',true);
});
on('themeDark','click',function(){applyTheme('dark',true);});
on('themeLight','click',function(){applyTheme('light',true);});
on('connectBtn','click',function(){
  if(state==='busy') return;
  if(state==='on'){ pendingReconnect=false; pendingServer=null; pendingFastest=false; }
  state==='on'?disconnect():connect();
});
on('refreshBtn','click',function(){
  var rb=$('refreshBtn');
  rb.classList.add('spin');
  try{ AndroidBridge.refreshServersForce(); }catch(e){ rb.classList.remove('spin'); }
});
on('dropdownBtn','click',function(){toggleList();});
on('countryList','click',function(e){
  if(!e.target.closest) return;
  var a=e.target.closest('[data-auto]');if(a){chooseFastest();return;}
  var s=e.target.closest('[data-sid]');
  if(s){
    var ip=s.getAttribute('data-sid'), port=s.getAttribute('data-port');
    var sv=byKey[pingKey(ip,port)]||serverByIp(ip);
    if(sv) chooseServer(sv);
    return;
  }
  var c=e.target.closest('[data-cc]');
  if(c){
    var fl=c.getAttribute('data-cc');
    var g=null;
    cur().groups.forEach(function(x){ if(x.cc===fl) g=x; });
    if(!g) return;
    /* Chevron (right ~48px of the row) expands the country; the row body connects to its best server. */
    var r=c.getBoundingClientRect();
    var x=e.clientX||0;
    if(x>r.right-48){ openCC=openCC===fl?null:fl; renderList(); }
    else { chooseCountry(g); }
  }
});
var copyBtns=document.querySelectorAll('[data-copy]');
for(var ci=0;ci<copyBtns.length;ci++){(function(btn){
  btn.addEventListener('click',function(){
    var v=btn.getAttribute('data-copy');
    var done=function(){toast('Copied · '+v);};
    function fb(){try{var t=document.createElement('textarea');t.value=v;t.style.position='fixed';t.style.opacity='0';
      document.body.appendChild(t);t.select();document.execCommand('copy');document.body.removeChild(t);done();}catch(e){toast(v);}}
    try{if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(v).then(done,fb);}else fb();}catch(e){fb();}
  });})(copyBtns[ci]);}
on('projectLink','click',function(e){
  if(Bridge.call('openExternal','https://github.com/ProxyArc/ArcVeePN')) e.preventDefault();
});
var tgBtns=document.querySelectorAll('[data-tg]');
for(var ti=0;ti<tgBtns.length;ti++){tgBtns[ti].addEventListener('click',function(e){
  if(Bridge.call('openExternal','tg://resolve?domain=ProxyArc')) e.preventDefault();});}
document.addEventListener('keydown',function(e){
  if(e.key==='Escape'){
    if(appsOpen) closeApps();
    else if(panelOpen) setPanel(false);
    else toggleList(false);
  }
  if(e.key==='Tab'&&panelOpen){
    var scope=appsOpen?$('appView'):$('panel');
    var all=scope.querySelectorAll('button, a[href], input'),f=[],k;
    for(k=0;k<all.length;k++){if(all[k].offsetParent!==null&&!all[k].disabled)f.push(all[k]);}
    if(!f.length) return;
    var a=f[0],b=f[f.length-1];
    if(e.shiftKey&&document.activeElement===a){e.preventDefault();b.focus();}
    else if(!e.shiftKey&&document.activeElement===b){e.preventDefault();a.focus();}
  }
});
(function(){
  var sx=0,sy=0,dx=0,act=false,dec=false,p=$('panel');
  p.addEventListener('touchstart',function(e){if(!panelOpen||appsOpen)return;var t=e.touches[0];sx=t.clientX;sy=t.clientY;dx=0;act=true;dec=false;},{passive:true});
  p.addEventListener('touchmove',function(e){if(!act)return;var t=e.touches[0],mx=t.clientX-sx,my=t.clientY-sy;
    if(!dec){if(Math.abs(mx)<8&&Math.abs(my)<8)return;dec=true;if(Math.abs(my)>Math.abs(mx)){act=false;return;}p.classList.add('drag');}
    dx=Math.max(0,mx);p.style.transform='translateX('+dx+'px)';if(e.cancelable)e.preventDefault();},{passive:false});
  p.addEventListener('touchend',function(){if(!act)return;act=false;p.classList.remove('drag');p.style.transform='';if(dx>72)setPanel(false);});
})();

/* ===== boot ===== */
(function(){
  var savedTheme=store.get('proxyarc-theme');
  if(!savedTheme){
    try{ savedTheme=window.matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'; }catch(e){ savedTheme='dark'; }
  }
  applyTheme(savedTheme==='light'?'light':'dark',false);
  loadSettings();
  /* Cached list first — the dropdown is never empty on open, even with bad internet. */
  try{
    var cached=JSON.parse(AndroidBridge.getCachedServers());
    if(cached&&cached.servers&&cached.servers.length){ applyServerList(cached.servers); }
  }catch(e){}
  renderList(); paintTrigger(); setState('idle');
  setInterval(poll,1000);
  /* Splash (the design's intro) — visual only; the servers and ping sweep run behind it. */
  var splashDone=false;
  function finishSplash(){
    if(splashDone) return; splashDone=true;
    var sp=$('splash'); if(sp){ sp.classList.add('done'); }
    $('app').classList.add('ready');
    setTimeout(function(){ var sp2=$('splash'); if(sp2) sp2.style.display='none'; },560);
  }
  (function(){
    var dur=reduce?400:1700,t0=null;
    var M=[[0,'Loading servers'],[45,'Building directory'],[78,'Starting ping test'],[100,'Ready']];
    function tick(ts){
      if(t0===null) t0=ts;
      var p=Math.min(100,Math.round((ts-t0)/dur*100));
      var f=$('spFill'); if(f) f.style.width=p+'%';
      var pc=$('spPct'); if(pc) pc.textContent=p+'%';
      var mg=$('spMsg');
      if(mg){ for(var i=M.length-1;i>=0;i--){ if(p>=M[i][0]){ mg.textContent=M[i][1]; break; } } }
      if(p<100) requestAnimationFrame(tick);
      else setTimeout(finishSplash,reduce?40:220);
    }
    requestAnimationFrame(tick);
    /* Safety: the app must never stay hidden if the animation stalls. */
    setTimeout(finishSplash,3500);
  })();
  try{ AndroidBridge.refreshServersForce(); }catch(e){}
  try{ AndroidBridge.checkExitIp(); }catch(e){}
  setTimeout(poll,500);
})();
})();
