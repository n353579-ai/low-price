
import React from 'react';
import { PriceResult, ProductInfo } from '../types';

interface PriceResultsProps {
  results: PriceResult[];
  product: ProductInfo | null;
  onReset: () => void;
  isStreaming: boolean;
}

const PriceResults: React.FC<PriceResultsProps> = ({ results, product, onReset, isStreaming }) => {
  return (
    <div className="flex flex-col h-full bg-[#f8f9fa] overflow-y-auto">
      {/* Header Section */}
      <div className="bg-[#1a1a1a] p-10 pt-16 text-white shadow-xl relative overflow-hidden">
        <div className="absolute top-0 right-0 w-48 h-48 bg-amber-400/10 rounded-full blur-3xl -mr-24 -mt-24"></div>
        <div className="relative z-10">
          <h2 className="text-lg font-bold text-amber-400/80 mb-2 tracking-widest uppercase">Detected Item</h2>
          <p className="text-4xl font-black break-words leading-tight tracking-tight">
            {product ? `${product.brand} ${product.name}` : '제품 분석 중...'}
          </p>
        </div>
      </div>

      {/* Results List */}
      <div className="p-6 space-y-6 flex-grow pb-48">
        <div className="flex items-center justify-between px-2">
          <h3 className="text-2xl font-black text-zinc-900">대한민국 최저가 TOP 5</h3>
          {isStreaming && (
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 bg-red-500 rounded-full animate-ping"></span>
              <span className="text-xs font-bold text-red-500 uppercase">Live Finding...</span>
            </div>
          )}
        </div>
        
        {results.length === 0 && isStreaming && (
          <div className="flex flex-col items-center py-20 opacity-30">
            <div className="w-12 h-12 border-4 border-zinc-200 border-t-amber-400 rounded-full animate-spin"></div>
            <p className="mt-4 font-bold text-xl">가격을 불러오고 있습니다...</p>
          </div>
        )}

        <div className="grid gap-5">
          {results.map((res, index) => (
            <a
              key={index}
              href={res.url}
              target="_blank"
              rel="noopener noreferrer"
              className="block bg-white rounded-[1.5rem] p-6 card-shadow border border-zinc-100 active:scale-[0.98] transition-transform animate-in slide-in-from-bottom-4 duration-300"
            >
              <div className="flex flex-col gap-3">
                <div className="flex justify-between items-center">
                  <div className={`px-4 py-1 rounded-full text-xs font-black uppercase tracking-widest ${index === 0 ? 'bg-amber-400 text-zinc-900' : 'bg-zinc-100 text-zinc-400'}`}>
                    {index + 1}위
                  </div>
                  <div className="text-xl font-bold text-zinc-600">{res.mallName}</div>
                </div>
                
                <div className="flex justify-between items-end border-t border-zinc-50 pt-3 mt-1">
                  <p className="text-4xl font-black text-zinc-900 tracking-tighter">{res.price}</p>
                  <div className="text-amber-500 text-2xl font-bold">바로가기 →</div>
                </div>
              </div>
            </a>
          ))}
        </div>
      </div>

      {/* Sticky Bottom Branding & Reset */}
      <div className="fixed bottom-0 left-0 right-0 p-8 bg-white/80 backdrop-blur-xl border-t border-zinc-100 flex flex-col items-center gap-4 shadow-[0_-10px_30px_rgba(0,0,0,0.05)]">
        <button
          onClick={onReset}
          className="w-full bg-[#1a1a1a] text-white py-6 rounded-2xl text-2xl font-black shadow-xl active:scale-95 transition-transform"
        >
          다른 제품 찍기
        </button>
        <div className="opacity-30 text-[10px] tracking-[0.3em] text-black uppercase font-medium">
          Powered by 지무어
        </div>
      </div>
    </div>
  );
};

export default PriceResults;
