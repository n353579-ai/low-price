
import React, { useRef, useEffect, useState } from 'react';

interface CameraScannerProps {
  onCapture: (base64Image: string) => void;
  isLoading: boolean;
}

const CameraScanner: React.FC<CameraScannerProps> = ({ onCapture, isLoading }) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [countdown, setCountdown] = useState(2.0);

  useEffect(() => {
    async function startCamera() {
      try {
        const mediaStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment' },
          audio: false,
        });
        setStream(mediaStream);
        if (videoRef.current) {
          videoRef.current.srcObject = mediaStream;
        }
      } catch (err) {
        alert("카메라를 켤 수 없습니다. 권한을 확인해주세요.");
      }
    }
    startCamera();
    return () => {
      stream?.getTracks().forEach(track => track.stop());
    };
  }, []);

  useEffect(() => {
    if (isLoading) return;

    const timer = setInterval(() => {
      setCountdown(prev => {
        if (prev <= 0.1) {
          clearInterval(timer);
          captureFrame();
          return 0;
        }
        return prev - 0.1;
      });
    }, 100);

    return () => clearInterval(timer);
  }, [isLoading]);

  const captureFrame = () => {
    if (videoRef.current && canvasRef.current) {
      const context = canvasRef.current.getContext('2d');
      if (context) {
        canvasRef.current.width = videoRef.current.videoWidth;
        canvasRef.current.height = videoRef.current.videoHeight;
        context.drawImage(videoRef.current, 0, 0);
        const base64 = canvasRef.current.toDataURL('image/jpeg', 0.8).split(',')[1];
        onCapture(base64);
      }
    }
  };

  return (
    <div className="relative w-full h-full flex flex-col bg-[#1a1a1a]">
      {/* Top Instruction */}
      <div className="absolute top-8 left-0 right-0 z-20 text-center pointer-events-none px-6">
        <div className="inline-flex flex-col items-center">
          <span className="bg-black/60 backdrop-blur-md text-white/90 text-lg font-medium px-6 py-2 rounded-full tracking-tight border border-white/10 shadow-2xl">
            물건을 사각형 안에 2초간 멈춰주세요
          </span>
          <div className="mt-4 w-48 h-1.5 bg-white/20 rounded-full overflow-hidden border border-white/5">
            <div 
              className="h-full bg-amber-400 transition-all duration-100 ease-linear"
              style={{ width: `${(1 - countdown / 2.0) * 100}%` }}
            ></div>
          </div>
        </div>
      </div>

      <video
        ref={videoRef}
        autoPlay
        playsInline
        className="flex-grow object-cover opacity-90"
      />
      <canvas ref={canvasRef} className="hidden" />
      
      {/* Scanning Frame */}
      <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
        <div className="w-72 h-72 relative">
          <div className="absolute top-0 left-0 w-12 h-12 border-t-4 border-l-4 border-amber-400/80 rounded-tl-2xl"></div>
          <div className="absolute top-0 right-0 w-12 h-12 border-t-4 border-r-4 border-amber-400/80 rounded-tr-2xl"></div>
          <div className="absolute bottom-0 left-0 w-12 h-12 border-b-4 border-l-4 border-amber-400/80 rounded-bl-2xl"></div>
          <div className="absolute bottom-0 right-0 w-12 h-12 border-b-4 border-r-4 border-amber-400/80 rounded-br-2xl"></div>
          <div className="absolute inset-0 bg-amber-400/5 animate-pulse rounded-2xl"></div>
        </div>
      </div>

      {/* Bottom Branding */}
      <div className="absolute bottom-0 left-0 right-0 flex flex-col items-center pb-12">
        <div className="opacity-40 text-[10px] tracking-[0.3em] text-white/80 uppercase font-light">
          Powered by 지무어
        </div>
      </div>
    </div>
  );
};

export default CameraScanner;
