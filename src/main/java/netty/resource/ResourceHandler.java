package netty.resource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.AsciiString;
import io.netty.util.CharsetUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * created by Rock-Ayl on 2019-11-26
 * 静态资源处理器
 */
public class ResourceHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    //静态资源-国际文件最后修改时间格式
    public static final SimpleDateFormat SDF_HTTP_DATE_FORMATTER = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);

    //服务器支持headers缓存
    private static Set<AsciiString> Headers = new HashSet<>();

    static {
        //基础headers
        Headers.add(HttpHeaderNames.CONTENT_TYPE);
        Headers.add(HttpHeaderNames.CONTENT_LENGTH);
        Headers.add(HttpHeaderNames.AUTHORIZATION);
        Headers.add(HttpHeaderNames.ACCEPT);
        Headers.add(HttpHeaderNames.ORIGIN);
        //用来判断是否为ajax
        Headers.add(AsciiString.cached("X-Requested-With"));
        //用户CookieId
        Headers.add(AsciiString.cached("cookieId"));
    }

    /**
     * 处理静态文件资源
     *
     * @param ctx
     * @param req
     * @param uriPath
     */
    public RandomAccessFile handleResource(ChannelHandlerContext ctx, HttpRequest req, String uriPath) {
        //初始化文件流
        RandomAccessFile randomAccessFile = null;
        try {
            //判空
            if (StringUtils.isNotEmpty(uriPath)) {
                //解析文件名称
                String fileBaseName = FilenameUtils.getBaseName(uriPath);
                //解析文件后缀
                String fileExt = FilenameUtils.getExtension(uriPath);
                //解码并组装成静态文件path
                String resourceFilePath = URLDecoder.decode(fileBaseName, "UTF-8") + "." + fileExt;
                //获取服务器中的静态文件
                File file = readResourceFile(resourceFilePath);
                //如果是个文件
                if (file.exists() && file.isFile()) {
                    //如果静态文件没有改动,直接返回(让浏览器用缓存)
                    if (isNotModified(req, file)) {
                        //文件未被修改,浏览器可以延用缓存
                        sendObject(ctx, HttpResponseStatus.NOT_MODIFIED, "Modified false.");
                    } else {
                        //获取文件流
                        randomAccessFile = new RandomAccessFile(file, "r");
                        //响应请求文件流
                        sendFileStream(ctx, req, file, randomAccessFile);
                    }
                } else {
                    //不存在文件,响应失败
                    sendObject(ctx, HttpResponseStatus.NOT_FOUND, "没有发现文件.");
                }
            }
        } catch (IOException e) {
            //错误日志
            System.out.println("handleResource IOException:" + e);
        } finally {
            //返回文件流
            return randomAccessFile;
        }
    }

    /**
     * 响应一般http请求并返回object(这里既可以是json,也可以是普通文本)
     *
     * @param ctx
     * @param status
     * @param result 返回结果,一般为Json
     */
    public static void sendObject(ChannelHandlerContext ctx, HttpResponseStatus status, Object result) {
        //创建一个新缓冲
        ByteBuf content = Unpooled.copiedBuffer(result.toString(), CharsetUtil.UTF_8);
        //请求初始化
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        //判空
        if (content != null) {
            //组装content_type
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json;charset=UTF-8");
            //组装content_length
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        }
        //添加通用参数,告诉浏览器服务的名字,是否支持长链接,支持的请求类型,支持的参数,跨域等等
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.SERVER, "nettyDemo");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, true);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, Collections.unmodifiableSet(getAccessHeaders()));
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, 86400);
        //响应并关闭通道
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 读取服务器静态资源
     *
     * @param pathSuffix 资源路径后缀
     * @return
     */
    public File readResourceFile(String pathSuffix) {
        //todo 静态资源目录自己设置
        return new File("/Users/ayl/workspace/resource/" + pathSuffix);
    }

    /**
     * 响应并返回请求文件的文件流
     *
     * @param ctx
     * @throws IOException
     */
    public static void sendFileStream(ChannelHandlerContext ctx, HttpRequest request, File file, RandomAccessFile randomAccessFile) throws IOException {
        //文件名
        String fileName = file.getName();
        //获取文件后缀
        String fileExt = FilenameUtils.getExtension(fileName);
        //文件长度
        long fileLength = randomAccessFile.length();
        //国际标准文件最后修改时间
        String fileLastModified = SDF_HTTP_DATE_FORMATTER.format(new Date(file.lastModified()));
        //当前时间
        long thisTime = System.currentTimeMillis();
        //一个基础的OK请求,我们使用http1.1协议,状态是200,也就是OK
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        //文件起始字节位置初始化
        long startOffset = 0;
        //文件结束字节位置初始化
        long endOffset = fileLength - 1;
        //传输文件的实际总长度
        long endLength = fileLength;
        //获取range值
        String range = request.headers().get(HttpHeaderNames.RANGE);
        //Range判空
        if (StringUtils.isNotEmpty(range)) {
            //设置为分片下载状态(由正常的200->206)
            response.setStatus(HttpResponseStatus.PARTIAL_CONTENT);
            //解析Range前后区间
            String[] r = range.replace("bytes=", "").split("-");
            //设置文件起始字节位置
            startOffset = Long.parseLong(r[0]);
            //判断是否存在文件结束字节位置
            if (r.length == 2) {
                //文件结束字节位置
                endOffset = Long.parseLong(r[1]);
            }
            //设置响应范围
            response.headers().set(HttpHeaderNames.CONTENT_RANGE, HttpHeaderValues.BYTES + " " + startOffset + "-" + endOffset + "/" + fileLength);
            //传输文件的实际总长度
            endLength = endOffset - startOffset + 1;
        }
        //初始化文件类型
        String contentType;
        //设定化内容处理:以附件的形式下载、文件名、编码
        String disposition;
        //指定缓存机制
        String cacheControl;
        //按文件类别区分文件类型
        contentType = parseHttpResponseContentType(fileName);
        //告诉浏览器是预览,会按照文件类型酌情预览,如果不支持则默认下载
        disposition = "inline";
        //根据文件后缀操作设置headers
        switch (fileExt) {
            case "html":
                //设置必须资源效验
                cacheControl = "no-cache";
                //文件实体标签,用于效验文件未修改性
                response.headers().set(HttpHeaderNames.ETAG, getFileMd5(file));
                break;
            case "js":
            case "css":
                //设置缓存时间为1年
                cacheControl = "max-age=31536000";
                //设置文件最后修改时间
                response.headers().set(HttpHeaderNames.LAST_MODIFIED, fileLastModified);
                break;
            default:
                //设置缓存时间为1天
                cacheControl = "max-age=86400";
                //设置文件最后修改时间
                response.headers().set(HttpHeaderNames.LAST_MODIFIED, fileLastModified);
                break;
        }
        //支持告诉客户端支持分片下载,如迅雷等多线程
        response.headers().set(HttpHeaderNames.ACCEPT_RANGES, HttpHeaderValues.BYTES);
        //handlers添加文件实际传输长度
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, endLength);
        //文件内容类型
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        //指定缓存机制
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, cacheControl);
        //文件名,是否 save as
        response.headers().add(HttpHeaderNames.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + URLEncoder.encode(fileName, "utf-8"));
        //该资源发送的时间
        response.headers().set(HttpHeaderNames.DATE, SDF_HTTP_DATE_FORMATTER.format(thisTime));
        //添加通用参数,告诉浏览器服务的名字,是否支持长链接,支持的请求类型,支持的参数,跨域等等
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.SERVER, "nettyDemo");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, true);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, Collections.unmodifiableSet(getAccessHeaders()));
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, 86400);
        //写入响应及对应响应报文
        ctx.write(response);
        //判断是否为https
        if (isHttps(ctx)) {
            //https的传输文件方式,非零拷贝,低效,不推荐
            ctx.writeAndFlush(new HttpChunkedInput(new ChunkedFile(randomAccessFile, startOffset, endLength, 8192)), ctx.newProgressivePromise());
        } else {
            //http默认的传输文件方式,零拷贝,高效
            ctx.writeAndFlush(new DefaultFileRegion(randomAccessFile.getChannel(), startOffset, endLength), ctx.newProgressivePromise());
        }
        //ctx响应并关闭(如果使用Chunked编码，最后则需要发送一个编码结束的看空消息体，进行标记，表示所有消息体已经成功发送完成)
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    /**
     * 检测静态文件是否没有修改过
     * 1.修改过,浏览器就必须重新刷新文件
     * 2.未修改过,浏览器延用缓存
     *
     * @param req  请求
     * @param file 服务器文件
     * @return
     */
    private boolean isNotModified(HttpRequest req, File file) {
        //默认修改过
        boolean notModified = false;
        //获取请求给与的浏览器缓存的文件最后修改时间
        String ifModifiedSince = req.headers().get(HttpHeaderNames.IF_MODIFIED_SINCE);
        try {
            //如果存在文件最后修改时间
            if (StringUtils.isNotEmpty(ifModifiedSince)) {
                //转化为时间戳并变为秒
                long ifModifiedSinceDateSeconds = SDF_HTTP_DATE_FORMATTER.parse(ifModifiedSince).getTime();
                //获取服务器静态文件最后修改时间
                long fileLastModifiedSeconds = file.lastModified();
                //如果相同
                if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                    //浏览器不需要修改缓存静态文件
                    notModified = true;
                }
            }
        } catch (Exception e) {
            System.out.println("检测缓存文件是否修改出错:" + e);
        } finally {
            return notModified;
        }
    }

    /**
     * 获取我们请求支持的参数
     *
     * @return
     */
    public static Set<AsciiString> getAccessHeaders() {
        return Headers;
    }

    /**
     * 获取文件MD5值
     * 经过测试:消耗较小内存下,6.3G文件需要18秒左右转化时间
     *
     * @param file
     * @return MD5值
     */
    public static String getFileMd5(File file) {
        try {
            return DigestUtils.md5Hex(new FileInputStream(file));
        } catch (IOException e) {
            System.out.println("文件转化MD5失败, IOException:" + e);
            return "";
        }
    }

    /**
     * 根据文件名返回CONTENT_TYPE
     *
     * @param fileName 文件路径
     * @return
     */
    public static String parseHttpResponseContentType(String fileName) {
        //获取文件后缀
        String fileExt = FilenameUtils.getExtension(fileName);
        //判空
        if (StringUtils.isNotBlank(fileExt)) {
            //小写
            fileExt = fileExt.toLowerCase();
            //分发
            switch (fileExt) {
                case "txt":
                case "html":
                    return "text/html; charset=UTF-8";
                case "text":
                    return "text/plain; charset=UTF-8";
                case "json":
                    return "application/json; charset=UTF-8";
                case "css":
                    return "text/css; charset=UTF-8";
                case "js":
                    return "application/javascript;charset=utf-8";
                case "svg":
                    return "Image/svg+xml; charset=utf-8";
                case "jpeg":
                case "jpg":
                    return "image/jpeg";
                case "csv":
                    return ".csv";
                case "ico":
                    return "image/x-icon";
                case "png":
                    return "image/png";
                case "pdf":
                    return "application/pdf; charset=utf-8";
                case "gif":
                    return "image/gif";
                case "mp3":
                    return "audio/mp3; charset=utf-8";
                case "mp4":
                case "mkv":
                    return "video/mp4; charset=utf-8";
            }
        }
        //缺省
        return "application/octet-stream";
    }

    /**
     * 判断一个请求是否为https即拥有SSL
     *
     * @param ctx
     * @return
     */
    public static boolean isHttps(ChannelHandlerContext ctx) {
        if (ctx.pipeline().get(SslHandler.class) != null) {
            return true;
        }
        return false;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        //获取get请求中的路径
        String url = msg.uri();
        //处理
        handleResource(ctx, msg, url);
    }

}
