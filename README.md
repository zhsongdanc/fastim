好像项目没有更新完

## FastIM
> 🚀基于Netty高可用分布式即时通讯系统，支持长连接网关管理、单聊、群聊、离线消息、消息推送消息、消息已读未读、消息未读数、红包、消息漫游等功能，支持集群部署的分布式架构。


## 一、功能
* [ ] 单聊
* [ ] 群聊
* [ ] 心跳保活重连
* [ ] 消息未读计数
* [x] [客户端SDK](#客户端SDK)
* [x] [消息服务SDK](#消息服务SDK)
* [ ] 长连接网关
* [ ] 短连接网关
* [ ] 消息漫游
* [ ] 消息加密
* [ ] 红包消息
* [ ] 消息已读

## 二、系统设计
### 1. IM架构图
基于可扩展性高可用原则，把网关层、逻辑层、数据层分离，并且支持分布式部署
![IM架构图](https://github.com/zhangyaoo/fastim/blob/master/pic/architecture_new.png)

### 2. 架构设计
#### 2.0 CLIENT设计：
1. client每个设备会在本地存每一个会话，保留有最新一条消息的顺序 ID
2. 为了避免client宕机，也就是退出应用，保存在内存的消息ID丢失，会存到本地的文件中
3. client需要在本地维护一个等待ack队列，并配合timer超时机制，来记录哪些消息没有收到ack：N，以定时重发。
4. 客户端本地生成一个递增序列号发送给服务器，用作保证发送顺序性。该序列号还用作ack队列收消息时候的移除。

#### 2.1 LSB设计与优化：
##### 2.1.0 LSB设计
1. 接入层的高可用、负载均衡、扩展性全部在这里面做
2. 客户端通过LSB，来获取gate IP地址，通过IP直连，目的是
    - 灵活的负载均衡策略 可根据最少连接数来分配IP
    - 做灰度策略来分配IP
    - AppId业务隔离策略 不同业务连接不同的gate，防止相互影响
    - 单聊和群聊的im接入层通道分开
    
##### 2.1.1 LSB优化
问题背景：当某个实例重启后，该实例的连接断开后，客户端会发起重连，重连就大概率转移其他实例上，导致最近启动的实例连接数较少，最早启动的实例连接数较多
解决方法：
1. 客户端会发起重连，跟服务器申请重连的新的服务器IP，系统提供合适的算法来平摊gate层的压力，防止雪崩效应。
2. gate层定时上报本机的元数据信息以及连接数信息，提供给LSB中心，LSB根据最少连接数负载均衡实现，来计算一个节点供连接。

#### 2.2 GATE设计：
GATE层网关有以下特性：
1. 任何一个gate网关断掉，用户端检测到以后重新连接LSB服务获取另一个gate网关IP，拿到IP重新进行长连接通信。对整体服务可靠性基本没有影响。
2. gate可以无状态的横向部署，来扩展接入层的接入能力
3. 根据协议分类将入口请求打到不同的网关上去，HTTP网关接收HTTP请求，TCP网关接收tcp长连接请求
4. 长连接网关，提供各种监控功能，比如网关执行线程数、队列任务数、ByteBuf使用堆内存数、堆外内存数、消息上行和下行的数量以及时间

#### 2.3 LOGIC和路由SDK设计：
1. logic按照分布式微服务的拆分思想进行拆分，拆分为多个模块，集群部署：
    - 消息服务
    - 红包服务
    - 其他服务

2. logic消息服务集成路由客户端SDK，SDK职责：
    - 负责和网关底层通信交互
    - 负责网关服务寻址
    - 负责存储uid和gate层机器ID关系（有状态：多级缓存避免和中间件多次交互。无状态：在业务初期可以不用存）
    - 配合网关负责路由信息一致性保证
        - 如果路由状态和channel通道不一致，比如有路由状态，没有channel通道（已关闭）那么，就会走离线消息流出，并且清除路由信息
        - 动态重启gate，会及时清理路由信息

3. SDK和网关底层通信设计：
![交互设计图](https://github.com/zhangyaoo/fastim/blob/master/pic/gate_sdk.png)
    - 网关层到服务层，只需要单向传输发请求，网关层不需要关心调用的结果。而客户端想要的ack或者notify请求是由SDK发送数据到网关层，SDK也不需要关心调用的结果，最后网关层只转发数据，不做额外的逻辑处理
    - SDK和所有的网关进行长连接，当发送信息给客户端时，根据路由寻址信息，即可通过长连接推送信息

### 3. 协议设计
#### 3.0 目标
1. 高性能：协议设计紧凑，保证数据包小，并且序列化性能好
2. 可扩展性：针对后续业务发展，可以自由的自定义协议，无需较大改动协议结构

#### 3.1 设计

IM协议采用二进制定长包头和变长包体来实现客户端和服务端的通信，并且采用谷歌protobuf序列化协议，设计如下：

![IM协议设计图](https://github.com/zhangyaoo/fastim/blob/master/pic/IM-protocol.png)

各个字段如下解释：
- headData：头部标识，协议头标识，用作粘包半包处理。4个字节
- version：客户端版本。4个字节
- cmd：业务命令，比如心跳、推送、单聊、群聊。1个字节
- msgType：消息通知类型 request response notify。1个字节
- logId：调试性日志，追溯一个请求的全路径。4个字节
- sequenceId：序列号，可以用作异步处理。4个字节
- dataLength：数据体的长度。4个字节
- data：数据

#### 3.2 实践
1. 针对数据data，**网关gate层不做反序列化，反序列化步骤在service做**，避免重复序列化和反序列化导致的性能损失
2. 网关层不做业务逻辑处理，只做消息转发和推送，减少网关层的复杂度

### 4. 安全管理
1. 为防止消息传输过程中不被截获、篡改、伪造，采用TLS传输层加密协议
2. 私有化协议天然具备一定的防窃取和防篡改的能力，相对于使用JSON、XML、HTML等明文传输系统，被第三方截获后在内容破解上相对成本更高，因此安全性上会更好一些
3. 消息存储安全性:针对账号密码的存储安全可以通过“高强度单向散列算法”和“加盐”机制来提升加密密码可逆性；IM消息采用“端到端加密”方式来提供更加安全的消息传输保护。
4. 安全层协议设计。基于动态密钥，借鉴类似SSL，不需要用证书来管理。

### 5. 消息流转设计
一个正常的消息流转需要如图所示的流程：
![IM核心流程图](https://github.com/zhangyaoo/fastim/blob/master/pic/IM-pic1.png)

1. 客户端A发送请求包R
2. server将消息存储到DB
3. 存储成功后返回确认ack
4. server push消息给客户端B
5. 客户端B收到消息后返回确认ack
6. server收到ack后更新消息的状态或者删除消息

需要考虑的是，一个健壮的系统需要考虑各种异常情况，如丢消息，重复消息，消息时序问题
#### 5.0 消息可靠性如何保证 不丢消息
1. 应用层ACK
2. 客户端需要超时与重传
3. 服务端需要超时与重传，具体做法就是增加ack队列和定时器Timer
4. 业务侧兜底保证，客户端拉消息通过一个本地的旧的序列号来拉取服务器的最新消息
5. 为了保证消息必达，在线客户端还增加一个定时器，定时向服务端拉取消息，避免服务端向客户端发送拉取通知的包丢失导致客户端未及时拉取数据。

#### 5.1 消息重复性如何保证 不重复
1. 超时与重传机制将导致接收的client收到重复的消息，具体做法就是一份消息使用同一个消息ID进行去重处理。

#### 5.2 消息顺序性如何保证 不乱序
##### 5.2.0 消息乱序影响的因素
1. 时钟不一致，分布式环境下每个机器的时间可能是不一致的
2. 多发送方和多接收方，这种情况下，无法保先发的消息被先收到
3. 网络传输和多线程，网络传输不稳定的话可能导致包在数据传输过程中有的慢有的快。多线程也可能是会导致时序不一致影响的因素

以上，如果保持绝对的实现，那么只能是一个发送方，一个接收方，一个线程阻塞式通讯来实现。那么性能会降低。

##### 5.2.1 如何保证时序
1. 单聊：通过发送方的绝对时序seq，来作为接收方的展现时序seq。
    - 实现方式：可以通过时间戳或者本地序列号方式来实现
    - 缺点：本地时间戳不准确或者本地序列号在意外情况下可能会清0，都会导致发送方的绝对时序不准确
 
2. 群聊：因为发送方多点发送时序不一致，所以通过服务器的单点做序列化，也就是通过ID递增发号器服务来生成seq，接收方通过seq来进行展现时序。
    - 实现方式：通过服务端统一生成唯一趋势递增消息ID来实现或者通过redis的递增incr来实现
    - 缺点：redis的递增incr来实现，redis取号都是从主取的，会有性能瓶颈。ID递增发号器服务是集群部署，可能不同发号服务上的集群时间戳不同，可能会导致后到的消息seq还小。
 
3. 群聊时序的优化：按照上面的群聊处理，业务上按照道理只需要保证单个群的时序，不需要保证所有群的绝对时序，所以解决思路就是**同一个群的消息落到同一个发号service**上面，消息seq通过service本地生成即可。

##### 5.2.2 客户端如何保证顺序
1. 为什么要保证顺序？
    - 消息即使按照顺序到达服务器端，也会可能出现：不同消息到达接收端后，可能会出现“先产生的消息后到”“后产生的消息先到”等问题。所以客户端需要进行兜底的流量整形机制

2. 如何保证顺序？
    - 接收方收到消息后进行判定，如果当前消息序号大于前一条消息的序号就将当前消息追加在会话里
    - 否则继续往前查找倒数第二条、第三条等消息，一直查找到恰好小于当前推送消息的那条消息，然后插入在其后展示。

### 6 消息通知设计
**整体消息推送和拉取的时序图如下：**
![IM消息推拉模型](https://github.com/zhangyaoo/fastim/blob/master/pic/msg-pull-push-model.png)

#### 6.0 消息拉取方式的选择
本项目是进行推拉结合来进行服务器端消息的推送和客户端的拉取，我们知道单pull和单push有以下缺点：

单pull：
- pull要考虑到消息的实时性，不知道消息何时送达
- pull要考虑到哪些好友和群收到了消息，要循环每个群和好友拿到消息列表，读扩散

单push：
- push实时性高，只要将消息推送给接收者就ok，但是会集中消耗服务器资源。并且再群聊非常多，聊天频率非常高的情况下，会增加客户端和服务端的网络交互次数

推拉结合：
- 推拉结合的方式能够分摊服务端的压力,能保证时效性，又能保证性能
- 具体做法就是有新消息时候，推送哪个好友或者哪个群有新消息，以及新消息的数量或者最新消息ID，客户端按需根据自身数据进行拉取


#### 6.1 推拉隔离设计
1. 为什么做隔离
    - 如果客户端一边正在拉取数据，一边有新的增量消息push过来
2. 如何做隔离
   - 本地设置一个全局的状态，当客户端拉取完离线消息后设置状态为1（表示离线消息拉取完毕）。当客户端收到拉取实时消息，会启用一个轮询监听这个状态，状态为1后，再去向服务器拉取消息。
   - 如果是push消息过来（不是主动拉取），那么会先将消息存储到本地的消息队列中，等待客户端上一次拉取数据完毕，然后将数据进行合并即可
 
### 7 消息ID生成设计
##### 7.0 设计
实际业务的情况【只做参考，实际可以根据公司业务线来调整】
1. 单机高峰并发量小于1W，预计未来5年单机高峰并发量小于10W
2. 有2个机房，预计未来5年机房数量小于4个 每个机房机器数小于150台
3. 目前只有单聊和群聊两个业务线，后续可以扩展为系统消息、聊天室、客服等业务线，最多8个业务线

根据以上业务情况，来设计分布式ID：
![IM服务端分布式ID设计](https://github.com/zhangyaoo/fastim/blob/master/pic/server-id.jpg)

##### 7.1 优点
- 不同机房不同机器不同业务线内生成的ID互不相同
- 每个机器的每毫秒内生成的ID不同
- 预留两位留作扩展位

##### 7.2 缺点：
当并发度不高的时候，时间跨毫秒的消息，区分不出来消息的先后顺序。因为时间跨毫秒的消息生成的ID后面的最后一位都是0，后续如果按照消息ID维度进行分库分表，会导致数据倾斜

##### 7.3 两种解决方案：
1. 方案一：去掉snowflake最后8位，然后对剩余的位进行取模
2. 方案二：不同毫秒的计数，每次不是归0，而是归为随机数，相比方案一，比较简单实用


### 8 消息未读数设计
#### 8.0 实现
1. 每发一个消息，消息接收者的会话未读数+1，并且接收者所有未读数+1
2. 消息接收者返回消息接收确认ack后，消息未读数会-1
3. 消息接收者的未读数+1，服务端就会推算有多少条未读数的通知

#### 8.1 分布式锁保证总未读数和会话未读数一致
1. 不一致原因：当总未读数增加，这个时候客户端来了请求将未知数置0，然后再增加会话未读数，那么会导致不一致
2. 保证：为了保证总未读数和会话未读数原子性，需要用分布式锁来保证

#### 8.2 群聊消息未读数难点和优化
1. 难点
    - 一个群聊每秒几百的并发聊天，比如消息未读数，相当于每秒W级别的写入redis，即便redis做了集群数据分片+主从，但是写入还是单节点，会有写入瓶颈

2. 优化
- 群ID分组或者用户ID分组，批量写入，写入的两种方式
    - 定时flush
    - 满多少消息进行flush


### 9. 网关设计
- 接入层网关和应用层网关不同地方
    - 接入层网关需要有接收通知包或者下行接收数据的端口，并且需要另外开启线程池。应用层网关不需要开端口，并且不需要开启线程池。
    - 接入层网关需要保持长连接，接入层网关需要本地缓存channel映射关系。应用层网关无状态不需要保存。

#### 9.1 接入层网关设计
##### 9.1.0 目标：
1. 网关的线程池实现1+8+4+1，减少线程切换
2. 集中实现长连接管理和推送能力
3. 与业务服务器解耦，集群部署缩容扩容以及重启升级不相互影响
4. 长连接的监控与报警能力
5. 客户端重连指令一键实现

##### 9.1.1 技术点：
- 支持自定义协议以及序列化
- 支持websocket协议
- 通道连接自定义保活以及心跳检测
- 本地缓存channel
- 责任链
- 服务调用完全异步
- 数据包转发
- 网关容错机制

##### 9.1.2 设计方案：
一个Notify包的数据经网关的线程模型图：
![TCP网关线程模型](https://github.com/zhangyaoo/fastim/blob/master/pic/TCP-Gate-ThreadModel.png)


#### 9.2 应用层API网关设计
##### 9.2.0 目标：
1. 基于版本的自动发现以及灰度/扩容 ，不需要关注IP
2. 网关的线程池实现1+8+1，减少线程切换
3. 支持协议转换实现多个协议转换，基于SPI来实现
4. 与业务服务器解耦，集群部署缩容扩容以及重启升级不相互影响
5. 接口错误信息统计和RT时间的监控和报警能力
6. UI界面实现路由算法，服务接口版本管理，灰度策略管理以及接口和服务信息展示能力
7. 基于OpenAPI提供接口级别的自动生成文档的功能

##### 9.2.1 技术点：
- Http2.0
- channel连接池复用
- Netty http 服务端编解码
- 责任链
- 服务调用完全异步
- 全链路超时机制
- 泛化调用

##### 9.2.2 设计方案：
一个请求包的数据经网关的架构图：
![网关的架构图](https://github.com/zhangyaoo/fastim/blob/master/pic/HTTP-gate.png)

### 10. 高并发、高可用设计
#### 10.0 高并发设计
##### 10.0.0 架构优化
- 水平扩展：各个模块无状态部署
- 线程模型：每个服务底层线程模型遵从Netty主从reactor模型
- 多层缓存：Gate层二级缓存，Redis一级缓存
- 长连接：客户端长连接保持，避免频繁创建连接消耗

##### 10.0.1 万人群聊优化
1. 难点
    - 消息扇出大，比如每秒群聊有50条消息，群聊2000人，那么光一个群对系统并发就有10W的消息扇出
 
2. 优化
    - 批量ACK：每条群消息都ACK，会给服务器造成巨大的冲击，为了减少ACK请求量，参考TCP的Delay ACK机制，在接收方层面进行批量ACK。
    - 群消息和成员批量加载以及懒加载：在真正进入一个群时才实时拉取群友的数据
    - 群离线消息过多：群消息分页拉取,第二次拉取请求作为第一次拉取请求的ack
    - 对于消息未读数场景，每个用户维护一个全局的未读数和每个会话的未读数，当群聊非常大时，未读资源变更的QPS非常大。这个时候应用层对未读数进行缓存，批量写+定时写来保证未读计数的写入性能
    - 路由信息存入redis会有写入和读取的性能瓶颈，每条消息在发出的时候会查路由信息来发送对应的gate接入层，比如有10个群，每个群1W，那么1s100条消息，那么1000W的查询会打满redis，即使redis做了集群。优化的思路就是将集中的路由信息分散到msg层 JVM本地内存中，然后做Route可用，避免单点故障。
    - 存储的优化，扩散写写入并发量巨大，另一方面也存在存储浪费，一般优化成扩散读的方式存储
    - 消息路由到相同接入层机器进行合并请求减少网络包传输
    
##### 10.0.2 代码优化
- 本地会话信息由一个hashmap保持，导致锁机制严重，按照用户标识进行hash,讲会话信息存在多个map中，减少锁竞争
- 利用双buffer机制，避免未读计数写入阻塞

##### 10.0.3 推拉结合优化合并
1. 背景：消息下发到群聊服务后，需要发送拉取通知给接收者，具体逻辑是群聊服务同步消息到路由层，路由层发送消息给接收者，接收者再来拉取消息。
2. 问题：如果消息连续发送或者对同一个接收者连续发送消息频率过高，会有许多的通知消息发送给路由层，消息量过大，可能会导致logic线程堆积，请求路由层阻塞。
3. 解决：发送者发送消息到逻辑层持久化后，将通知消息先存放一个队列中，相同的接收者接收消息通知消息后，更新相应的最新消息通知时间，然后轮训线程会轮训队列，将多个消息会合并为一个通知拉取发送至路由层，降低了客户端与服务端的网络消耗和服务器内部网络消耗。
4. 好处：保证同一时刻，下发线程一轮只会向同一用户发送一个通知拉取，一轮的时间可以自行控制

#### 10.1 高可用设计
##### 10.1.0 心跳设计
1. 服务端检测到某个客户端迟迟没有心跳过来可以主动关闭通道，让它下线，并且清除在线信息和路由信息；
2. 客户端检测到某个服务端迟迟没有响应心跳也能重连获取一个新的连接。
3. 智能心跳策略，比如正在发包的时候，不需要发送心跳。等待发包完毕后在开启心跳。并且自适应心跳策略调整。


##### 10.1.1 系统稳定性设计
1. 背景：高峰期系统压力大，偶发的网络波动或者机器过载，都有可能导致大量的系统失败。加上IM系统要求实时性，不能用异步处理实时发过来的消息。所以有了柔性保护机制防止雪崩

2. 柔性保护机制开启判断指标，当每个指标不在平均范围内的时候就开启
    - 每条消息的ack时间 RT时间
    - 同时在线人数以及同时发消息的人数
    - 每台机器的负载CPU和内存和网络IO和磁盘IO以及GC参数

3. 当开启了柔性保护机制，那么会返回失败，用户端体验不友好，如何优化
    - 当开启了柔性保护机制，逻辑层hold住多余的请求，返回前端成功，不显示发送失败，后端异步重试，直至成功；
    - 为了避免重试加剧系统过载，指数时间延迟重试
    
    
##### 10.1.2 异常场景设计
1. gate层重启升级或者意外down机有以下问题：
    - 客户端和gate意外丢失长连接，导致 客户端在发送消息的时候导致消息超时等待以及客户端重试等无意义操作
    - 发送给客户端的消息，从Msg消息层转发给gate的消息丢失，导致消息超时等待以及重试。

2. 解决方案如下：
    - 重启升级时候，向客户端发送重新连接指令，让客户端重新请求LSB获取IP直连。
    - 当gate层down机异常停止时候，增加hook钩子，向客户端发送重新连接指令。
    - 额外增加hook，向Msg消息层发送请求清空路由消息和在线状态，并且清除redis的路由信息。

    
   
##### 10.1.3 Redis宕机高可用设计
1. Redis作用背景
    - 当用户链接上网关后，网关会将用户的userId和机器信息存入redis，用作这个user接收消息时候，消息的路由
    - 消息服务在发消息给user时候，会查询Redis的路由信息，用来发送消息给哪个一个网关
 
2. 如果Redis宕机，会造成下面结果
	- 消息中转不过去，所有的用户可以发送消息，但是都接收不了消息
	- 如果有在线机制，那么系统都认为是离线状态，会走手机消息通道推送

3. Redis宕机兜底处理策略
	- 消息服务定时任务同步路由信息到本地缓存，如果redis挂了，从本地缓存拿消息
	- 网关服务在收到用户侧的上线和下线后，会同步广播本地的路由信息给各个消息服务，消息服务接收后更新本地环境数据
	- 网络交互次数多，以及消息服务多，可以用批量或者定时的方式同步广播路由消息给各个消息服务
  
   
### 11. 核心表结构设计
核心设计点
1. 群消息只存储一份，用户不需要为每个消息单独存一份。用户也无需去删除群消息。
2. 对于在线的用户，收到群消息后，修改这个last_ack_msg_id。
3. 对于离线用户，用户上线后，对比最新的消息ID和last_ack_msg_id，来进行拉取(参考Kafka的消费者模型)
4. 对应单聊，需要记录消息的送达状态，以便在异常情况下来做重试处理

#### 群用户消息表 t_group_user_msg 
字段|类型|描述
---|---|---
id|int|自增ID
group_id|int|群ID
user_id|bigint|用户ID
last_ack_msg_id|bigint|最后一次ack的消息ID
user_device_type|tinyint|用户设备类型
is_deleted|tinyint|是否删除,根据这个字段后续可以做冷备归档
create_time|datetime|创建时间
update_time|datetime|更新时间

#### 群消息表 t_group_msg
字段|类型|描述
---|---|---
id|int|自增ID
msg_id|bigint|消息ID
group_id|int|群ID
sender_id|bigint|发送方ID
msg_type|int|消息类型
msg_content|varchar|消息内容
is_deleted|tinyint|是否删除
create_time|datetime|创建时间
update_time|datetime|更新时间

### 12 核心业务流程
#### 12.0 用户A发消息给用户B 【单聊】
- A打包数据发送给服务端，服务端接收消息后，根据接收消息的sequence_id来进行客户端发送消息的去重，并且生成递增的消息ID，将发送的信息和ID打包一块入库，入库成功后返回ACK，ACK包带上服务端生成的消息ID
- 服务端检测接收用户B是否在线，在线直接推送给用户B
- 如果没有本地消息ID则存入，并且返回接入层ACK信息；如果有则拿本地sequence_id和推送过来的sequence_id大小对比，并且去重，进行展现时序进行排序展示，并且记录最新一条消息ID。最后返回接入层ack
- 服务端接收ACK后，将消息标为已送达
- 如果用户B不在线,首先将消息存入库中，然后直接通过手机通知来告知客户新消息到来
- 用户B上线后，拿本地最新的消息ID，去服务端拉取所有好友发送给B的消息，考虑到一次拉取所有消息数据量大，通过channel通道来进行分页拉取，将上一次拉取消息的最大的ID，作为请求参数，来请求最新一页的比ID大的数据。

#### 12.1 用户A发消息给群G 【群聊】
- 登录，TCP连接，token校验，名词检查，sequence_id去重，生成递增的消息ID，群消息入库成功返回发送方ACK
- 查询群G所有的成员，然后去redis中央存储中找在线状态。离线和在线成员分不同的方式处理
- 在线成员：并行发送拉取通知，等待在线成员过来拉取，发送拉取通知包如丢失会有兜底机制
- 在线成员过来拉取，会带上这个群标识和上一次拉取群的最小消息ID，服务端会找比这个消息ID大的所有的数据返回给客户端，等待客户端ACK。一段时间没ack继续推送。如果重试几次后没有回ack，那么关闭连接和清除ack等待队列消息
- 客户端会更新本地的最新的消息ID，然后进行ack回包。服务端收到ack后会更新群成员的最新的消息ID
- 离线成员：发送手机通知栏通知。离线成员上线后，拿本地最新的消息ID，去服务端拉取群G发送给A的消息，通过channel通道来进行分页拉取，每一次请求，会将上一次拉取消息的最大的ID，作为请求参数来拉取消息，这里相当于第二次拉取请求包是作为第一次拉取的ack包。
- 分页的情况下，客户端在收到上一页请求的的数据后更新本地的最新的消息ID后，再请求下一页并且带上消息ID。上一页请求的的数据可以当作为ack来返回服务端，避免网络多次交互。服务端收到ack后会更新群成员的最新的消息ID

### 13 红包设计

#### 13.1 抢红包的大致核心逻辑
1. 银行快捷支付，保证账户余额和发送红包逻辑的一致性
2. 发送红包后，首先计算好红包的个数，个数确定好后，确定好每个红包的金额，存入存储层【这里可以是redis的List或者是队列】方便后续每个人来取
3. 生成一个24小时的延迟任务，检测红包是否还有钱方便退回
4. 每个红包的金额需要保证每个红包的的抢金额概率是一致的，算法需要考量
5. 存入数据库表中后，服务器通过长连接，给群里notify红包消息,供群成员抢红包
6. 群成员并发抢红包，在第二步中会将每个红包的金额放入一个队列或者其他存储中，群成员实际是来竞争去队列中的红包金额。兜底机制：如果redis挂了，可以重新生成红包信息到数据库中
7. 取成功后，需要保证红包剩余金额、新插入的红包流水数据、队列中的红包数据以及群成员的余额账户金额一致性。
8. 这里还需要保证一个用户只能领取一次，并且保持幂等

## 三、项目结构
- fastim-logic：聚合逻辑服务工程层逻辑服务
- fastim-msg：消息服务，比如比如单聊、群聊、离线消息等消息服务
- fastim-msg-client：消息服务SDK逻辑服务，主要负责消息的路由转发接收消息等功能
- fastim-client：客户端SDK逻辑实现，实现发送消息、断线重连、SDK包等功能，支持不同协议链接
- fastim-gate-tcp：HTTP API网关实现，实现限流降级、版本路由、openAPI管理、协议转换、泛化调用等功能
- fastim-gate-http：长连接TCP网关实现，实现自定义协议、channel管理、心跳检测、泛化调用等功能
- fastim-leaf：分布式ID实现，基于zookeeper的实现或基于redis实现或完全基于内存的实现
- fastim-common：共用类，主要是实体类和工具类的存放
- fastim-lsb：LSB service，提供接入层IP和port来进行负载均衡的连接，并且监控长连接网关机
- fastim-sample：使用案例


## 四、性能测试

## 五、Q&A
0. Q：相比传统HTTP请求的业务系统，IM业务系统的有哪些不一样的设计难点？
   - 在线状态维护。相比于HTTP请求的业务系统，接入层有状态，必须维持心跳和会话状态，加大了系统设计复杂度。
   - 请求通信模型不一样。相比于HTTP请求一个request等待一个response通信模型，IM系统则是一个数据包在全双工长连接通道双传输，客户端和服务端消息交互的信令数据包设计复杂。
      
1. Q：对于单聊和群聊的实时性消息，是否需要MQ来作为通信的中间件来代替rpc？

   A：MQ作为解耦可以有以下好处：
   - 易扩展，gate层到logic层无需路由，logic层多个有新的业务时候，只需要监听新的topic即可
   - 解耦，gate层到logic层解耦，不会有依赖关系
   - 节省端口资源，gate层无需再开启新的端口接收logic的请求，而且直接监听MQ消息即可
   
   但是缺点也有：
   - 网络通信多一次网络通信，增加RT的时间，消息实时性对于IM即使通信的场景是非常注重的一个点
   - MQ的稳定性，不管任何系统只要引入中间件都会有稳定性问题，需要考虑MQ不可用或者丢失数据的情况
   - 需要考虑到运维的成本
   - 当用消息中间代替路由层的时候，gate层需要广播消费消息，这个时候gate层会接收大部分的无效消息，因为这个消息的接收者channel不在本机维护的session中
   
   综上，是否考虑使用MQ需要架构师去考量，比如考虑业务是否允许、或者系统的流量、或者高可用设计等等影响因素。
   本项目基于使用成本、耦合成本和运维成本考虑，采用Netty作为底层自定义通信方案来实现，也能同样实现层级调用。
   
2. Q：为什么接入层用LSB返回的IP来做接入呢？

   A：可以有以下好处：1、灵活的负载均衡策略 可根据最少连接数来分配IP；2、做灰度策略来分配IP；3、AppId业务隔离策略 不同业务连接不同的gate，防止相互影响
   
3. Q：为什么应用层心跳对连接进行健康检查？

   A：因为TCP Keepalive状态无法反应应用层状态问题，如进程阻塞、死锁、TCP缓冲区满等情况；并且要注意心跳的频率，频率小则可能及时感知不到应用情况，频率大可能有一定的性能开销。
   
4. Q：MQ的使用场景？

   A：IM消息是非常庞大的，比如说群聊相关业务、推送，对于一些业务上可以忍受的场景，尽量使用MQ来解耦和通信，来降低同步通讯的服务器压力。
   
5. Q：群消息存一份还是多份，读扩散还是写扩散？

   A：存1份，读扩散。存多份下同一条消息存储了很多次，对磁盘和带宽造成了很大的浪费。可以在架构上和业务上进行优化，来实现读扩散。
   
6. Q：消息ID为什么是趋势递增就可以，严格递增的不行吗？

   A：严格递增会有单点性能瓶颈，比如MySQL auto increments；redis性能好但是没有业务语义，比如缺少时间因素，还可能会有数据丢失的风险，并且集群环境下写入ID也属于单点，属于集中式生成服务。小型IM可以根据业务场景需求直接使用redis的incr命令来实现IM消息唯一ID。本项目采用snowflake算法实现唯一趋势递增ID，即可实现IM消息中，时序性，重复性以及查找功能。

7. Q：gate层为什么需要开两个端口？

   A：gate会接收客户端的连接请求（被动），需要外网监听端口；entry会主动给logic发请求（主动）；entry会接收服务端给它的通知请求（被动），需要内网监听端口。一个端口对内，一个端口对外。

8. Q：用户的路由信息，是维护在中央存储的redis中，还是维护在每个msg层内存中？
   - 维护在每个msg层内存中有状态：多级缓存避免和中间件多次交互, 并发高
   - 维护在中央存储的redis中，msg层无状态，redis压力大，每次交互IO网络请求大
   业务初期为了减少复杂度，可以维护在Redis中
   
9. Q：网关层和服务层以及msg层和网关层请求模型具体是怎样的？
   - 网关层到服务层，只需要单向传输发请求，网关层不需要关心调用的结果。而客户端想要的ack或者notify请求是由SDK发送数据到网关层，SDK也不需要关心调用的结果，最后网关层只转发数据，不做额外的逻辑处理。
   - SDK和所有的网关进行长连接，当发送信息给客户端时，根据路由寻址信息，即可通过长连接推送信息
   
10. Q：本地写数据成功，一定代表对端应用侧接收读取消息了吗？
   A：本地TCP写操作成功，但数据可能还在本地写缓冲区中、网络链路设备中、对端读缓冲区中，并不代表对端应用读取到了数据。

11. Q：为什么用netty做来做http网关, 而不用tomcat？
    - netty对象池，内存池，高性能线程模型
    - netty堆外内存管理，减少GC压力，jvm管理的只是一个很小的DirectByteBuffer对象引用
    - tomcat读取数据和写入数据都需要从内核态缓冲copy到用户态的JVM中，多1次或者2次的拷贝会有性能影响
    
12. Q：为什么消息入库后，对于在线状态的用户，单聊直接推送，群聊通知客户端来拉取，而不是直接推送消息给客户端（推拉结合）？
   A：在保证消息实时性的前提下，对于单聊，直接推送。对于群聊，由于群聊人数多，推送的话一份群消息会对群内所有的用户都产生一份推送的消息，推送量巨大。解决办法是按需拉取，当群消息有新消息时候发送时候，服务端主动推送新的消息数量，然后客户端分页按需拉取数据。
    
13. Q：为什么除了单聊 群聊 推送 离线拉取等实时性业务，其他的业务都走http协议？
   A：IM协议简单最好，如果让其他的业务请求混进IM协议中，会让其IM变的更复杂，比如查找离线消息记录拉取走http通道避免tcp 通道压力过大，影响即时消息下发效率。在比如上传图片和大文件，可以利用HTTP的断点上传和分段上传特性
  
14. Q：机集群机器要考虑到哪些优化？
   A：网络宽带；最大文件句柄；每个tcp的内存占用；Linux系统内核tcp参数优化配置；网络IO模型；网络网络协议解析效率；心跳频率；会话数据一致性保证；服务集群动态扩容缩容
  
## 六、Contact
- 网站：zhangyaoo.github.io
- 微信：will_zhangyao
