
export interface PriceResult {
  rank: number;
  mallName: string;
  price: string;
  url: string;
}

export interface ProductInfo {
  name: string;
  brand: string;
}

export enum AppState {
  IDLE = 'IDLE',
  SCANNING = 'SCANNING',
  IDENTIFYING = 'IDENTIFYING',
  SEARCHING = 'SEARCHING',
  RESULTS = 'RESULTS',
  ERROR = 'ERROR'
}
