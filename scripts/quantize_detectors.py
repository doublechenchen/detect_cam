"""Quantize detector heads only; retain FP16 embedding and enrollment compatibility."""
import argparse
from pathlib import Path
import cv2,numpy as np
from rknn.api import RKNN
p=argparse.ArgumentParser();p.add_argument('--root',required=True);a=p.parse_args();root=Path(a.root);out=root/'detect_cam/models';cal=out/'calibration';cal.mkdir(exist_ok=True)
paths=sorted((root/'training/five_sku_20260910/dataset/images/train').glob('*.jpg'))[::20][:30]
paths+=sorted((root/'data/sku110k_yolo/images/train').glob('*.jpg'))[:10]
rows=[]
for n,path in enumerate(paths):
 im=cv2.imread(str(path));h,w=im.shape[:2];scale=min(640/w,640/h);nw,nh=round(w*scale),round(h*scale)
 image=np.full((640,640,3),114,np.uint8);x,y=(640-nw)//2,(640-nh)//2;image[y:y+nh,x:x+nw]=cv2.resize(im,(nw,nh))
 dst=cal/f'{n}.png';cv2.imwrite(str(dst),image);rows.append(str(dst.resolve()))
assert len(rows)>=10
listing=cal/'images.txt';listing.write_text('\n'.join(rows)+'\n')
for src,name in [(out/'containers_640.onnx','containers_640_int8.rknn'),(root/'training/sku_det_20260915/sku_detector_640.onnx','chips_640_int8.rknn')]:
 r=RKNN(verbose=False)
 try:
  assert r.config(target_platform='rk3588',mean_values=[[0,0,0]],std_values=[[255,255,255]],optimization_level=3,quantized_dtype='w8a8',quantized_method='channel')==0
  assert r.load_onnx(model=str(src))==0
  assert r.build(do_quantization=True,dataset=str(listing))==0
  assert r.export_rknn(str(out/name))==0
 finally:r.release()
 print('exported',name,flush=True)
