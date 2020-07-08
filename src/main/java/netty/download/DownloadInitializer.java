package netty.download;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Created By Rock-Ayl on 2020-07-08
 * 定义处理器
 */
public class DownloadInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * 一个请求过来,我们要挨个按照如下顺序处理,最后是我们自己写的处理器
     *
     * @param ch
     */
    @Override
    public void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        //http解码+编码处理器
        pipeline.addLast(new HttpServerCodec());
        //netty是基于分段请求的，HttpObjectAggregator的作用是将HTTP消息的多个部分合成一条完整的HTTP消息,参数是聚合字节的最大长度
        pipeline.addLast(new HttpObjectAggregator(65536));
        //以块的方式来写的处理器，解决大码流的问题，ChunkedWriteHandler：可以向客户端发送HTML5文件
        pipeline.addLast(new ChunkedWriteHandler());
        //自定义的下载处理器
        pipeline.addLast(new DownloadFileHandler());
    }

}
