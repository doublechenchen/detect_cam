"""Export local YOLOv8n COCO bottle/cup head to nine-output RKNN. No downloads."""
import argparse,types,json,hashlib
from pathlib import Path
import torch,numpy as np,onnxruntime as ort
from ultralytics import YOLO
from rknn.api import RKNN
p=argparse.ArgumentParser();p.add_argument('--weights',required=True);p.add_argument('--out',required=True);a=p.parse_args()
out=Path(a.out);out.mkdir(parents=True,exist_ok=True);torch.set_num_threads(4)
net=YOLO(a.weights).model.cpu().float().eval();net.fuse();head=net.model[-1]
def forward(self,features):
 result=[]
 for i,f in enumerate(features):
  reg=self.cv2[i](f);cls=self.cv3[i](f).sigmoid()[:,[39,41]]
  result.extend([reg,cls,cls.sum(1,keepdim=True).clamp(0,1)])
 return tuple(result)
head.forward=types.MethodType(forward,head)
x=torch.rand(1,3,640,640);target=out/'containers_640.onnx'
with torch.no_grad():
 ref=net(x);torch.onnx.export(net,x,str(target),opset_version=12,input_names=['images'],dynamo=False)
opts=ort.SessionOptions();opts.intra_op_num_threads=4
actual=ort.InferenceSession(str(target),sess_options=opts).run(None,{'images':x.numpy()})
for lhs,rhs in zip(actual,ref):np.testing.assert_allclose(lhs,rhs.numpy(),atol=.001,rtol=.001)
r=RKNN(verbose=False)
try:
 assert r.config(target_platform='rk3588',mean_values=[[0,0,0]],std_values=[[255,255,255]],optimization_level=3)==0
 assert r.load_onnx(model=str(target))==0
 assert r.build(do_quantization=False)==0
 assert r.export_rknn(str(out/'containers_640_fp16.rknn'))==0
finally:r.release()
(out/'containers_640.json').write_text(json.dumps({'source':str(Path(a.weights).resolve()),'sha256':hashlib.sha256(Path(a.weights).read_bytes()).hexdigest(),'classes':['bottle','cup'],'coco_class_ids':[39,41],'input':'RGB UINT8 NHWC; letterbox 114; /255 fused','outputs':[list(v.shape) for v in actual],'onnx_parity':'passed atol/rtol=1e-3','target':'rk3588','precision':'FP16'},indent=2))
print('Export and ONNX parity passed')
