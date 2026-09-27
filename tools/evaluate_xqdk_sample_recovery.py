from pathlib import Path
import numpy as np, json, statistics, hashlib
from datetime import datetime, timezone
from importlib.metadata import version
from ai_edge_litert.interpreter import Interpreter
from PIL import Image
from math import sqrt
"""Offline TFLite/LiteRT sample probe. This is a Python reproduction of Android preprocessing, postprocessing, and grid fitting; it is not an Android runtime or ground-truth accuracy test. Requires numpy, Pillow, and ai-edge-litert."""
PROJECT=Path(__file__).resolve().parents[1]
root=Path('/workspace/测试log')
imgs=sorted((root/'最新1.3.3版本log').glob('*.png'))+sorted((root/'最新log，异常卡死和识别错误').glob('*.png'))+[root/'识别出错.png',root/'错误的识别结果。.png']
models={'Medium':str(PROJECT/'app/src/main/assets/yolov5m_xq_fp32.tflite'),'Lite':str(PROJECT/'app/src/main/assets/yolov5n_xq_fp16.tflite')}

def iou(a,b):
 x1=max(a[0],b[0]);y1=max(a[1],b[1]);x2=min(a[2],b[2]);y2=min(a[3],b[3]);inter=max(0,x2-x1)*max(0,y2-y1);aa=max(0,a[2]-a[0])*max(0,a[3]-a[1]);bb=max(0,b[2]-b[0])*max(0,b[3]-b[1]);return inter/(aa+bb-inter) if aa+bb-inter else 0

def decode(raw, fw,fh, conf=.45, margin=.05, offset=(0,0), board_conf=None):
 s=min(640/fw,640/fh);nw=round(fw*s);nh=round(fh*s);px=(640-nw)//2;py=(640-nh)//2
 raws=[]
 for row in raw:
  obj=float(row[4]);
  min_conf=conf if board_conf is None else min(conf,board_conf)
  if obj<min_conf:continue
  ss=obj*row[5:];cls=int(np.argmax(ss));score=float(ss[cls]);
  class_conf=board_conf if cls==14 and board_conf is not None else conf
  if score<class_conf:continue
  second=float(np.max(np.delete(ss,cls)))
  if score-second<margin:continue
  cx,cy,bw,bh=map(float,row[:4]);box=[(cx-bw/2-px)/s,(cy-bh/2-py)/s,(cx+bw/2-px)/s,(cy+bh/2-py)/s]
  if bw<=0 or bh<=0 or not(.5<=bw/bh<=1.6):continue
  raws.append({'c':cls,'s':score,'x':(box[0]+box[2])/2+offset[0],'y':(box[1]+box[3])/2+offset[1],'w':box[2]-box[0],'h':box[3]-box[1],'b':box})
 raws.sort(key=lambda d:-d['s']);keep=[]
 for d in raws:
  if any(k['c']==d['c'] and iou(d['b'],k['b'])>.45 for k in keep):continue
  keep.append(d)
 pieces=[d for d in keep if d['c']!=14]
 if not pieces:return keep
 mw=float(np.median([d['w'] for d in pieces]));mh=float(np.median([d['h'] for d in pieces]));board=max((d for d in keep if d['c']==14),key=lambda d:d['s'],default=None)
 geom=None
 if board and .7<=board['w']/board['h']<=1.3 and board['w']>=mw*7 and board['h']>=mh*8:
  cw=board['w']/8;ch=board['h']/9;x0=board['x']-board['w']/2;y0=board['y']-board['h']/2;geom=(x0,y0,x0+board['w'],y0+board['h'],cw,ch)
 out=[];minf=max(.4,.55) if len(pieces)<8 else .4
 for d in keep:
  if d['c']==14:out.append(d);continue
  if geom:
   x0,y0,x1,y1,cw,ch=geom;ok=(x0-cw*.6<=d['x']<=x1+cw*.6 and y0-ch*.6<=d['y']<=y1+ch*.6 and cw*.25<=d['w']<=cw*2.2 and ch*.25<=d['h']<=ch*2.2)
  else:ok=(mw*minf<=d['w']<=mw*2 and mh*minf<=d['h']<=mh*2)
  if ok:out.append(d)
 return out


def fit_axis(values, board0, board1, cell_count):
 base=(board1-board0)/float(cell_count)
 if base<=0 or len(values)<3:return None
 min_support=max(3,int(np.ceil(len(values)*.50)))
 best=None
 def consider(origin,step):
  nonlocal best
  if not np.isfinite(origin) or not np.isfinite(step) or step<=0:return
  if origin>board1+base*.75 or origin+cell_count*step<board0-base*.75:return
  residuals=[];support=0
  for value in values:
   idx=int(np.floor((value-origin)/step+0.5))
   if idx<0 or idx>cell_count:continue
   residual=abs(value-(origin+idx*step))/step
   residuals.append(residual)
   if residual<=.32:support+=1
  if support<min_support or not residuals:return
  median=sorted(residuals)[len(residuals)//2]
  penalty=abs(origin-board0)/base+abs(step-base)/base
  if best is None or support>best[2] or (support==best[2] and (median<best[3]-1e-6 or (abs(median-best[3])<=1e-6 and penalty<best[4]))):
   best=(origin,step,support,median,penalty)
 for si in range(41):
  step=base*(.80+si*.01)
  for shift_i in range(45):
   shift=-.45+shift_i*.025
   consider(board0+shift*base,step)
  for value in values:
   for idx in range(cell_count+1): consider(value-idx*step,step)
 return best

def refine_board_grid(x0,y0,x1,y1,pieces):
 basew=(x1-x0)/8.0;baseh=(y1-y0)/9.0
 if basew<=0 or baseh<=0 or len(pieces)<3:return None
 fit=[p for p in pieces if x0-basew*.75<=p['x']<=x1+basew*.75 and y0-baseh*.75<=p['y']<=y1+baseh*.75]
 if len(fit)<3:return None
 xf=fit_axis([p['x'] for p in fit],x0,x1,8);yf=fit_axis([p['y'] for p in fit],y0,y1,9)
 if xf is None or yf is None:return None
 support_limit=max(3,int(np.ceil(len(pieces)*.65)))
 residuals=[]
 for p in fit:
  col=int(np.floor((p['x']-xf[0])/xf[1]+.5));row=int(np.floor((p['y']-yf[0])/yf[1]+.5))
  if not(0<=col<=8 and 0<=row<=9):continue
  rx=abs(p['x']-(xf[0]+col*xf[1]))/xf[1];ry=abs(p['y']-(yf[0]+row*yf[1]))/yf[1]
  residuals.append(max(rx,ry))
 close=sum(r<=.32 for r in residuals);med=sorted(residuals)[len(residuals)//2] if residuals else float('inf')
 if close<support_limit or med>.32:return None
 if xf[0]>x1+basew*.75 or xf[0]+8*xf[1]<x0-basew*.75 or yf[0]>y1+baseh*.75 or yf[0]+9*yf[1]<y0-baseh*.75:return None
 return xf[0],yf[0],xf[0]+8*xf[1],yf[0]+9*yf[1]

def mapboard(dets):
 pieces=[d for d in dets if d['c']!=14];bd=max((d for d in dets if d['c']==14),key=lambda d:d['s'],default=None)
 if len(pieces)<3:return None
 if bd:
  bx0=bd['x']-bd['w']/2;by0=bd['y']-bd['h']/2;bx1=bd['x']+bd['w']/2;by1=bd['y']+bd['h']/2
  refined=refine_board_grid(bx0,by0,bx1,by1,pieces)
  if refined is not None:x0,y0,x1,y1=refined
  else:x0,y0,x1,y1=bx0,by0,bx1,by1
 else:
  if len(pieces)<12:return None
  x0=min(d['x'] for d in pieces);x1=max(d['x'] for d in pieces);y0=min(d['y'] for d in pieces);y1=max(d['y'] for d in pieces)
  if x1<=x0 or y1<=y0 or not(.7<=(x1-x0)/(y1-y0)<=1.3):return None
  md=min(sqrt((a['x']-b['x'])**2+(a['y']-b['y'])**2) for i,a in enumerate(pieces) for b in pieces[i+1:])
  if y1-y0<md*8 or x1-x0<md*7:return None
 gx=(x1-x0)/8;gy=(y1-y0)/9;cells={};con=[];drop=0
 for d in pieces:
  col=round((d['x']-x0)/gx);row=round((d['y']-y0)/gy)
  if not 0<=col<9 or not 0<=row<10:drop+=1;continue
  key=(row,col)
  if key in cells and cells[key]['c']!=d['c']:con.append((key,cells[key]['c'],d['c'],cells[key]['s'],d['s']))
  if key not in cells or d['s']>cells[key]['s']:cells[key]=d
 nred=sum(d['c']==10 for d in cells.values());nblack=sum(d['c']==3 for d in cells.values())
 # approximate key safety gates, exact rules run by app beyond this
 return {'anchor':'BOARD' if bd else 'BBOX','piece_n':len(pieces),'mapped':len(cells),'redK':nred,'blackK':nblack,'conf':con,'drop':drop,'safe_geometry':bool(nred==1 and nblack==1 and not con and len(cells)>0),'bbox':[round(x,1) for x in (x0,y0,x1,y1)],'cells':{f'{r},{c}':d['c'] for (r,c),d in cells.items()}}

out={}
for name,path in models.items():
 it=Interpreter(model_path=path,num_threads=1);it.allocate_tensors();di=it.get_input_details()[0];do=it.get_output_details()[0];rows={}
 for p in imgs:
  im=Image.open(p).convert('RGB');sw,sh=im.size;scale=min(1,1440/max(sw,sh));cw=max(1,round(sw*scale));ch=max(1,round(sh*scale));frame=im.resize((cw,ch),Image.Resampling.BILINEAR) if (cw,ch)!=(sw,sh) else im
  s=min(640/cw,640/ch);nw=round(cw*s);nh=round(ch*s);px=(640-nw)//2;py=(640-nh)//2
  a=np.full((640,640,3),114,dtype=np.uint8);a[py:py+nh,px:px+nw]=np.asarray(frame.resize((nw,nh),Image.Resampling.BILINEAR));x=a.astype(np.float32)/255
  it.set_tensor(di['index'],x[None]);it.invoke();raw=it.get_tensor(do['index'])[0]
  dets=decode(raw,cw,ch); m=mapboard(dets)
  zoom=None; zoom_kind=None
  bd=max((d for d in dets if d['c']==14),key=lambda d:d['s'],default=None)
  needs_zoom=(m is None and not bd and sum(d['c']!=14 for d in dets)>=12) or (m is not None and (m['redK']!=1 or m['blackK']!=1 or bool(m['conf'])))
  if needs_zoom and bd:
   x0,y0,x1,y1=bd['b'];cellw=(x1-x0)/8;cellh=(y1-y0)/9; margin=.6
   cx0=max(0,round(x0-margin*cellw));cy0=max(0,round(y0-margin*cellh));cx1=min(cw,round(x1+margin*cellw));cy1=min(ch,round(y1+margin*cellh));zoom_kind='board'
  elif needs_zoom and ((m is not None and m['anchor']=='BBOX' and m['piece_n']>=12) or m is None):
   if m is not None:
    x0,y0,x1,y1=m['bbox']
   else:
    pp=[d for d in dets if d['c']!=14];x0=min(d['x'] for d in pp);x1=max(d['x'] for d in pp);y0=min(d['y'] for d in pp);y1=max(d['y'] for d in pp)
   cellw=(x1-x0)/8;cellh=(y1-y0)/9
   cx0=max(0,round(x0-1.5*cellw));cy0=max(0,round(y0-1.5*cellh));cx1=min(cw,round(x1+1.5*cellw));cy1=min(ch,round(y1+1.5*cellh));zoom_kind='bbox'
  if needs_zoom and zoom_kind:
   crop=frame.crop((cx0,cy0,cx1,cy1)); ww,hh=crop.size;ss=min(640/ww,640/hh);nnw=round(ww*ss);nnh=round(hh*ss);ppx=(640-nnw)//2;ppy=(640-nnh)//2;aa=np.full((640,640,3),114,dtype=np.uint8);aa[ppy:ppy+nnh,ppx:ppx+nnw]=np.asarray(crop.resize((nnw,nnh),Image.Resampling.BILINEAR));it.set_tensor(di['index'],aa.astype(np.float32)[None]/255);it.invoke();rr=it.get_tensor(do['index'])[0]
   zd=decode(rr,ww,hh,offset=(cx0,cy0)); zoom=mapboard(zd)
  threshold_recovery=None
  threshold_sweep={}
  if name=='Lite':
   for thr in (.45,.46,.47,.48,.49,.50,.51,.52,.53,.54,.55):
    rd0=decode(raw,cw,ch,conf=thr,margin=.05,board_conf=.20)
    rm0=mapboard(rd0)
    threshold_sweep[str(thr)]=rm0
  if name=='Lite' and m is not None and m['conf']:
   rd=decode(raw,cw,ch,conf=.47,margin=.05,board_conf=.20)
   rm=mapboard(rd)
   accepted=bool(rm and rm['safe_geometry'] and rm['mapped']==m['mapped'] and rm.get('cells')==m.get('cells'))
   threshold_recovery={'mapped':rm,'accepted_by_current_detector_predicate':accepted,'piece_threshold':.47,'board_threshold':.20}
  rows[p.name]={'capture':[cw,ch],'nDet':len(dets),'nBoard':sum(d['c']==14 for d in dets),'mapped':m,'zoom':zoom,'zoom_kind':zoom_kind,'threshold_recovery':threshold_recovery,'threshold_sweep':threshold_sweep}
 out[name]=rows
report={
 'generated_at_utc':datetime.now(timezone.utc).isoformat(),
 'runtime':{'ai_edge_litert':version('ai-edge-litert'),'numpy':np.__version__,'pillow':version('pillow'),'threads':1},
 'input_pipeline':'RGB; resize longest edge to 1440 with PIL bilinear; resize/letterbox to 640x640 with value 114; float32 RGB normalized to 0..1; one TFLite invocation per base/ROI candidate.',
 'postprocess_reproduction':'YOLOv5 confidence/objectness and class-margin filtering, same-class NMS IoU 0.45, aspect/size filtering, Python port of DetectionBoardMapper.fitGridAxis/refineBoardGrid with the Kotlin search ranges/support thresholds, then grid mapping. This is an offline Python reproduction, not an Android/Kotlin execution.',
 'sample_files':[{'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'size':Image.open(p).size} for p in imgs],
 'models':{name:{'path':str(Path(path).relative_to(PROJECT)),'sha256':hashlib.sha256(Path(path).read_bytes()).hexdigest(),'bytes':Path(path).stat().st_size} for name,path in models.items()},
 'limitations':['No per-cell human ground truth; geometric candidate pass is not recognition accuracy.','Does not reproduce Android Canvas rasterization, screen overlay masking, service stability window, BoardSanitizer, AssistBoard.validate, engine safety, BoardTracker, or device runtime.','No target-device inference/performance log.'],
 'results':out,
}
for name,images in out.items():
 safe=0
 for r in images.values():
  base=r.get('mapped');zoom=r.get('zoom');recovery=r.get('threshold_recovery')
  chosen=recovery.get('mapped') if recovery and recovery.get('accepted_by_current_detector_predicate') else zoom if zoom and zoom.get('safe_geometry') else base
  safe+=bool(chosen and chosen.get('safe_geometry'))
 report.setdefault('geometric_candidate_summary',{})[name]={'count':safe,'total':len(images)}
print(json.dumps(report,ensure_ascii=False,indent=2))
