package com.p2plan.tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP IGD（Internet Gateway Device）端口映射：向家宽路由器请求「把本机某个 UDP 端口暴露到公网」。
 *
 * <p>这是提升打洞成功率最有效的一招，也是<b>唯一</b>能让对称型 NAT 也能被直连的办法：
 * 一旦映射成功，本机在公网上就是可达的，对方不需要打洞就能直接连过来。
 *
 * <p>实现走 UPnP 标准的三个 HTTP 步骤（纯 JDK，无第三方依赖）：
 * <ol>
 *   <li>SSDP 组播 {@code M-SEARCH} 找 IGD 设备；</li>
 *   <li>GET 设备描述 XML，取出 {@code controlURL}；</li>
 *   <li>SOAP {@code AddPortMapping} 添加映射（可选 {@code DeletePortMapping} 撤销）。</li>
 * </ol>
 *
 * <p>不支持的场景（多数手机热点、部分运营商 CGNAT、企业网）会安静失败并返回 null ——
 * 调用方应当把 UPnP 当作<b>加分项</b>，它失败不影响打洞与 IPv6 直连这两条主路。
 *
 * <p>ponytail: 只做最常用的 WANIPConnection:1 服务类型，且只映射 UDP（隧道用），不做 TCP 映射；
 * 不处理多网关/多 WAN 场景，取第一个响应的 IGD。升级路径：按 serviceType 逐个尝试、支持 WANPPPConnection、
 * 加 PCP/NAT-PMP 作为补充协议。
 */
public final class Upnp {

    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
    private static final int TIMEOUT_MS = 2500;

    /** 一次成功的映射。 */
    public record Mapping(String externalIp, int externalPort, int internalPort, String controlUrl) {
    }

    private Upnp() {
    }

    /**
     * 尝试为 {@code udpPort} 建立 UDP 端口映射。
     *
     * @return 成功返回映射信息（含公网 IP 与端口）；路由器不支持/无响应/被拒绝返回 null
     */
    public static Mapping mapUdp(int udpPort, String description) {
        try {
            String location = discoverIgd();
            if (location == null) {
                return null;
            }
            String controlUrl = findControlUrl(location);
            if (controlUrl == null) {
                return null;
            }
            String extIp = queryExternalIp(controlUrl);
            String localIp = localAddressToward(location);
            if (localIp == null) {
                return null;
            }
            boolean ok = addPortMapping(controlUrl, localIp, udpPort, description);
            if (!ok) {
                return null;
            }
            return new Mapping(extIp, udpPort, udpPort, controlUrl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 撤销之前建立的映射（尽力而为）。 */
    public static void unmapUdp(int udpPort, String controlUrl) {
        if (controlUrl == null) {
            return;
        }
        try {
            String body = soapEnvelope("DeletePortMapping",
                    "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + udpPort + "</NewExternalPort>"
                            + "<NewProtocol>UDP</NewProtocol>");
            httpPost(controlUrl, "DeletePortMapping", body);
        } catch (Throwable ignored) {
            // 撤销失败无所谓：映射会随租期到期
        }
    }

    // ------------------------------------------------------------------ SSDP

    private static String discoverIgd() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            String msg = "M-SEARCH * HTTP/1.1\r\n"
                    + "HOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\n"
                    + "MAN: \"ssdp:discover\"\r\n"
                    + "MX: 2\r\n"
                    + "ST: " + SEARCH_TARGET + "\r\n\r\n";
            byte[] data = msg.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(data, data.length,
                    InetAddress.getByName(SSDP_ADDR), SSDP_PORT));

            byte[] buf = new byte[2048];
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String resp = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                Matcher m = Pattern.compile("(?i)^LOCATION:\\s*(.+)$", Pattern.MULTILINE).matcher(resp);
                if (m.find()) {
                    return m.group(1).trim();
                }
            }
        } catch (Throwable ignored) {
            // 无 IGD 或网络不允许组播：安静失败
        }
        return null;
    }

    // ------------------------------------------------------------------ 描述 XML

    private static String findControlUrl(String location) {
        try {
            String xml = httpGet(location);
            if (xml == null) {
                return null;
            }
            // 优先找 WANIPConnection 的 controlURL（NAT 功能所在）
            Matcher service = Pattern.compile(
                    "(?is)<service>(.{0,4000}?)</service>").matcher(xml);
            String fallback = null;
            while (service.find()) {
                String block = service.group(1);
                Matcher cu = Pattern.compile("(?is)<controlURL>\\s*([^<]+?)\\s*</controlURL>").matcher(block);
                if (!cu.find()) {
                    continue;
                }
                String url = cu.group(1).trim();
                if (block.toLowerCase().contains("wanipconnection") || block.toLowerCase().contains("wanpppconnection")) {
                    return absolutize(location, url);
                }
                if (fallback == null) {
                    fallback = absolutize(location, url);
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String absolutize(String base, String path) {
        try {
            URL b = new URL(base);
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path;
            }
            if (path.startsWith("/")) {
                return b.getProtocol() + "://" + b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "") + path;
            }
            String dir = b.toString();
            dir = dir.substring(0, dir.lastIndexOf('/') + 1);
            return dir + path;
        } catch (Throwable t) {
            return path;
        }
    }

    // ------------------------------------------------------------------ SOAP

    private static String queryExternalIp(String controlUrl) {
        try {
            String body = soapEnvelope("GetExternalIPAddress", "");
            String resp = httpPost(controlUrl, "GetExternalIPAddress", body);
            if (resp == null) {
                return null;
            }
            Matcher m = Pattern.compile("(?is)<NewExternalIPAddress>\\s*([^<]+?)\\s*</NewExternalIPAddress>").matcher(resp);
            return m.find() ? m.group(1).trim() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean addPortMapping(String controlUrl, String localIp, int port, String desc) {
        try {
            String body = soapEnvelope("AddPortMapping",
                    "<NewRemoteHost></NewRemoteHost>"
                            + "<NewExternalPort>" + port + "</NewExternalPort>"
                            + "<NewProtocol>UDP</NewProtocol>"
                            + "<NewInternalPort>" + port + "</NewInternalPort>"
                            + "<NewInternalClient>" + localIp + "</NewInternalClient>"
                            + "<NewEnabled>1</NewEnabled>"
                            + "<NewPortMappingDescription>" + escapeXml(desc) + "</NewPortMappingDescription>"
                            + "<NewLeaseDuration>0</NewLeaseDuration>");
            String resp = httpPost(controlUrl, "AddPortMapping", body);
            return resp != null && resp.contains("AddPortMappingResponse");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String soapEnvelope(String action, String inner) {
        return "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">"
                + inner
                + "</u:" + action + "></s:Body></s:Envelope>";
    }

    // ------------------------------------------------------------------ HTTP

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("GET");
            return read(c.getInputStream());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String httpPost(String url, String action, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            c.setRequestProperty("SOAPAction",
                    "\"urn:schemas-upnp-org:service:WANIPConnection:1#" + action + "\"");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            return in == null ? null : read(in);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 256 * 1024) {
                break; // 设备描述不可能这么大，防跑飞
            }
        }
        in.close();
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String localAddressToward(String location) {
        try {
            URL u = new URL(location);
            InetAddress remote = InetAddress.getByName(u.getHost());
            // 连一个 UDP socket 让系统选出「去往路由器的那张网卡的地址」（不会真的发数据）
            try (DatagramSocket s = new DatagramSocket()) {
                s.connect(new InetSocketAddress(remote, u.getPort() > 0 ? u.getPort() : 80));
                return s.getLocalAddress().getHostAddress();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String escapeXml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}