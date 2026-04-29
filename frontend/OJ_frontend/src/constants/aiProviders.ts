export interface AiProvider {
  id: string;
  name: string;
  baseUrl: string;
  models: string[];
  color: string;
  initial: string;
}

export const AI_PROVIDERS: AiProvider[] = [
  {
    id: "dashscope",
    name: "阿里百炼",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    models: [
      "qwen-plus",
      "qwen-max",
      "qwen-turbo",
      "qwen-long",
      "deepseek-v3",
      "deepseek-r1",
    ],
    color: "#ff6a00",
    initial: "百",
  },
  {
    id: "deepseek",
    name: "DeepSeek",
    baseUrl: "https://api.deepseek.com/v1",
    models: ["deepseek-chat", "deepseek-reasoner"],
    color: "#4d6bfe",
    initial: "DS",
  },
  {
    id: "openai",
    name: "OpenAI",
    baseUrl: "https://api.openai.com/v1",
    models: ["gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o1", "o1-mini"],
    color: "#10a37f",
    initial: "AI",
  },
  {
    id: "zhipu",
    name: "智谱 AI",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4/",
    models: ["glm-4-plus", "glm-4-flash", "glm-4-long", "glm-4"],
    color: "#3451b2",
    initial: "智",
  },
  {
    id: "minimax",
    name: "MiniMax",
    baseUrl: "https://api.minimax.chat/v1/",
    models: ["abab6.5s-chat", "abab6.5-chat", "abab5.5-chat"],
    color: "#6c5ce7",
    initial: "MM",
  },
  {
    id: "siliconflow",
    name: "硅基流动",
    baseUrl: "https://api.siliconflow.cn/v1",
    models: [
      "Qwen/Qwen2.5-72B-Instruct",
      "deepseek-ai/DeepSeek-V3",
      "deepseek-ai/DeepSeek-R1",
    ],
    color: "#00b4d8",
    initial: "硅",
  },
  {
    id: "moonshot",
    name: "月之暗面",
    baseUrl: "https://api.moonshot.cn/v1",
    models: ["moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"],
    color: "#1a1a2e",
    initial: "月",
  },
];
