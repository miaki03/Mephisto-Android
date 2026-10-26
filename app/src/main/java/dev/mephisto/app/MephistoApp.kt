package dev.mephisto.app

import android.app.Application

/**
 * 应用级初始化。目前只做一个"占位"的明确记录：**没有**强制 IPv4。
 *
 * 试过 `System.setProperty("java.net.preferIPv4Stack", "true")`（怀疑 IPv6 黑洞导致首连慢），
 * 实测无改善：真正的原因是校园网到 Cloudflare 的 443 不通，不是 IPv6 优先的问题。
 * 强制 IPv4 还会在 IPv6-only/NAT64 网络下把连接彻底打断，所以不保留。
 * 首连慢的真正解法是"短连接超时 + 一次重试 + 明确失败提示"，已在 UrlConnectionTransport 里。
 */
class MephistoApp : Application()