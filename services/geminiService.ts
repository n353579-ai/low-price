
import { GoogleGenAI, Type } from "@google/genai";
import { PriceResult, ProductInfo } from "../types";

const getAIClient = () => {
  return new GoogleGenAI({ apiKey: process.env.API_KEY || '' });
};

export const identifyProduct = async (base64Image: string): Promise<ProductInfo> => {
  const ai = getAIClient();
  const response = await ai.models.generateContent({
    model: 'gemini-3-flash-preview',
    contents: {
      parts: [
        {
          inlineData: {
            mimeType: 'image/jpeg',
            data: base64Image,
          },
        },
        {
          text: `이 사진 속 제품이 무엇인지 브랜드와 모델명을 아주 짧고 정확하게 알려주세요. 
          결과는 반드시 JSON 형식으로 반환하세요.
          예시: {"brand": "삼성", "name": "RF85A9111AP"}`
        }
      ]
    },
    config: {
      responseMimeType: "application/json",
      responseSchema: {
        type: Type.OBJECT,
        properties: {
          brand: { type: Type.STRING },
          name: { type: Type.STRING }
        },
        required: ["brand", "name"]
      }
    }
  });

  try {
    return JSON.parse(response.text || '{}');
  } catch (e) {
    throw new Error("제품을 인식하지 못했습니다.");
  }
};

export async function* searchLowestPricesStream(product: ProductInfo): AsyncGenerator<PriceResult> {
  const ai = getAIClient();
  const query = `${product.brand} ${product.name} 최저가`;
  
  // Use generateContentStream for real-time output
  const responseStream = await ai.models.generateContentStream({
    model: 'gemini-3-flash-preview',
    contents: `
      구글 검색 결과 최상단 요약 정보를 바탕으로 ${query} 최저가 쇼핑몰 5곳을 빠르게 찾아주세요.
      정확성보다 속도가 중요합니다. 하나씩 찾을 때마다 즉시 아래 형식을 한 줄씩 출력하세요.
      
      형식: [순위] 쇼핑몰이름 | 가격 | URL
      
      예시:
      [1] 옥션 | 35,000원 | https://auction.co.kr
      [2] 지마켓 | 36,000원 | https://gmarket.co.kr
    `,
    config: {
      tools: [{ googleSearch: {} }],
    }
  });

  let buffer = "";
  const seenRanks = new Set<number>();

  for await (const chunk of responseStream) {
    buffer += chunk.text;
    
    // Parse the buffer for lines matching the pattern
    const lines = buffer.split('\n');
    // Keep the last potentially incomplete line in the buffer
    buffer = lines.pop() || "";

    for (const line of lines) {
      const match = line.match(/\[(\d+)\]\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*)/);
      if (match) {
        const rank = parseInt(match[1]);
        if (!seenRanks.has(rank)) {
          seenRanks.add(rank);
          yield {
            rank,
            mallName: match[2].trim(),
            price: match[3].trim(),
            url: match[4].trim()
          };
        }
      }
    }
  }
}
