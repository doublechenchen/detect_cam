"""Summarize DetectCamPerf log rows for one process; omit startup warmup."""
import argparse,json,math,re,statistics
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('log',type=Path)
p.add_argument('--pid',required=True)
p.add_argument('--skip',type=int,default=5)
p.add_argument('--output',type=Path)
a=p.parse_args()
rows=[]
for line in a.log.read_text().splitlines():
    fields=line.split()
    if len(fields)<5 or fields[2]!=a.pid or 'DetectCamPerf:' not in line: continue
    row={k:int(v) for k,v in re.findall(r'(\w+)=(\d+)',line)}
    if 'total' in row: rows.append(row)
rows=rows[a.skip:]
if not rows: raise SystemExit('No samples for selected process')
result={'pid':a.pid,'samples':len(rows),'warmup_frames_excluded':a.skip,
        'metric':'after I420 frame clone/submission to analysis completion; excludes sensor, prior conversion and UI rendering',
        'under_300ms_percent':round(100*sum(r['total']<300 for r in rows)/len(rows),2),'metrics':{}}
for key in ('total','queue','primary','secondary_decode','features','other','embeds','cached','boxes'):
    values=sorted(r[key] for r in rows)
    result['metrics'][key]={'mean':round(statistics.mean(values),2),'p50':statistics.median(values),
                            'p95':values[math.ceil(len(values)*.95)-1],'max':max(values)}
text=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
if a.output: a.output.write_text(text)
print(text)
