package netty.resource;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Created By Rock-Ayl on 2020-07-06
 * 静态资源服务
 */
public class ResourceServer {

    //netty监控的端口
    private static int port = 8888;

    public static void main(String[] args) {
        //负责接收客户端到端口的请求并交给 workerGroup,是个死循环
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        //从boss手里获得服务器的请求并处理,是个死循环
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        //通道,用来关闭服务端
        Channel channel;
        try {
            //用ServerBootstrap创建Server
            ServerBootstrap bootstrap = new ServerBootstrap()
                    //将boss和工作者放入
                    .group(bossGroup, workerGroup)
                    //初始化一个新的Channel去接收到达的connection,这是我们netty主要使用的
                    .channel(NioServerSocketChannel.class)
                    //一些固定的,暂时不需要你知道的配置
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
            //配置你自己定义的处理器
            bootstrap.childHandler(new ResourceInitializer());
            //绑定服务器监听端口
            ChannelFuture channelFuture = bootstrap.bind(port).sync();
            System.out.println("Netty服务已开启");
            //获取通道
            channel = channelFuture.channel();
            //一个监听器,负责箭筒通道关闭的
            channel.closeFuture().sync();
        } catch (Exception e) {
            System.out.println("服务设定的接口[" + port + "]已经被占用");
            //直接关闭
            System.exit(-1);
        } finally {
            //优雅的关闭worker
            workerGroup.shutdownGracefully();
            //优雅的关闭boss
            bossGroup.shutdownGracefully();
        }
    }

}
