
import React, { useState, useCallback } from 'react';
import { AppState, PriceResult, ProductInfo } from './types';
import CameraScanner from './components/CameraScanner';
import PriceResults from './components/PriceResults';
import { identifyProduct, searchLowestPricesStream } from './services/geminiService';

const App: React.FC = () => {
  const [state, setState] = useState<AppState>(AppState.SCANNING);
  const [product, setProduct] = useState<ProductInfo | null>(null);
  const [results, setResults] = useState<PriceResult[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [isStreaming, setIsStreaming] = useState(false);

  const handleCapture = useCallback(async (base64Image: string) => {
    setState(AppState.IDENTIFYING);
    setError(null);
    setResults([]);
    
    try {
      // 1. Identify product from image (Quick Flash Model)
      const productInfo = await identifyProduct(base64Image);
      setProduct(productInfo);
      
      // 2. Start streaming search
      setState(AppState.RESULTS);
      setIsStreaming(true);
      
      const stream = searchLowestPricesStream(productInfo);
      for await (const result of stream) {
        setResults(prev => {
          // Add result only if it doesn't exist yet to prevent duplicates during stream parsing
          if (prev.find(r => r.rank === result.rank)) return prev;
          const newResults = [...prev, result];
          return newResults.sort((a, b) => a.rank - b.rank);
        });
      }
      setIsStreaming(false);
    } catch (err: any) {
      console.error(err);
      setError(err.message || "분석에 실패했습니다. 밝은 곳에서 다시 찍어주세요.");
      setState(AppState.ERROR);
    }
  }, []);

  const handleReset = () => {
    setProduct(null);
    setResults([]);
    setError(null);
    setIsStreaming(false);
    setState(AppState.SCANNING);
  };

  return (
    <div className="fixed inset-0 flex flex-col bg-[#1a1a1a] overflow-hidden text-white">
      {state === AppState.SCANNING && (
        <CameraScanner onCapture={handleCapture} isLoading={false} />
      )}

      {state === AppState.IDENTIFYING && (
        <div className="flex flex-col items-center justify-center h-full bg-[#1a1a1a] p-10 text-center">
          <div className="relative w-48 h-48 mb-12">
            <div className="absolute inset-0 border-[12px] border-zinc-800 rounded-full shadow-inner"></div>
            <div className="absolute inset-0 border-[12px] border-amber-400 border-t-transparent rounded-full animate-spin"></div>
            <div className="absolute inset-4 border-[2px] border-amber-400/20 rounded-full animate-pulse"></div>
          </div>
          <p className="text-5xl font-black text-white leading-tight tracking-tighter">
            무엇인지<br/>분석하는 중...
          </p>
          <div className="absolute bottom-12 opacity-20 text-[10px] tracking-[0.3em] uppercase">
            Powered by 지무어
          </div>
        </div>
      )}

      {state === AppState.RESULTS && (
        <PriceResults 
          results={results} 
          product={product} 
          onReset={handleReset} 
          isStreaming={isStreaming} 
        />
      )}

      {state === AppState.ERROR && (
        <div className="flex flex-col items-center justify-center h-full bg-[#1a1a1a] p-10 text-center">
          <div className="text-9xl mb-12 opacity-80 drop-shadow-2xl">⚠️</div>
          <h2 className="text-4xl font-black text-amber-500 mb-6 uppercase tracking-tight">시스템 오류</h2>
          <p className="text-2xl text-zinc-400 mb-16 font-medium leading-relaxed max-w-sm">
            {error}
          </p>
          <button
            onClick={handleReset}
            className="w-full max-w-sm bg-white text-zinc-900 py-6 rounded-2xl text-2xl font-black shadow-xl active:scale-95 transition-transform"
          >
            다시 찍기
          </button>
        </div>
      )}
    </div>
  );
};

export default App;
