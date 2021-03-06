package com.youlai.mall.oms.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.youlai.common.enums.BusinessTypeEnum;
import com.youlai.common.redis.component.BusinessNoGenerator;
import com.youlai.common.result.Result;
import com.youlai.common.web.exception.BizException;
import com.youlai.common.web.util.RequestUtils;
import com.youlai.mall.oms.enums.OrderStatusEnum;
import com.youlai.mall.oms.enums.OrderTypeEnum;
import com.youlai.mall.oms.enums.PayTypeEnum;
import com.youlai.mall.oms.mapper.OrderMapper;
import com.youlai.mall.oms.pojo.domain.OmsOrder;
import com.youlai.mall.oms.pojo.domain.OmsOrderItem;
import com.youlai.mall.oms.pojo.dto.OrderConfirmDTO;
import com.youlai.mall.oms.pojo.dto.OrderItemDTO;
import com.youlai.mall.oms.pojo.dto.OrderSubmitDTO;
import com.youlai.mall.oms.pojo.vo.CartVO;
import com.youlai.mall.oms.pojo.vo.OrderConfirmVO;
import com.youlai.mall.oms.pojo.vo.OrderSubmitVO;
import com.youlai.mall.oms.service.ICartService;
import com.youlai.mall.oms.service.IOrderItemService;
import com.youlai.mall.oms.service.IOrderService;
import com.youlai.mall.pms.api.app.PmsSkuFeignService;
import com.youlai.mall.pms.pojo.dto.SkuDTO;
import com.youlai.mall.pms.pojo.dto.SkuLockDTO;
import com.youlai.mall.ums.api.MemberAddressFeignClient;
import com.youlai.mall.ums.api.MemberFeignClient;
import com.youlai.mall.ums.pojo.domain.UmsAddress;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.youlai.mall.oms.constant.OmsConstants.*;

@AllArgsConstructor
@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, OmsOrder> implements IOrderService {

    private ICartService cartService;
    private PmsSkuFeignService skuFeignService;
    private MemberAddressFeignClient addressFeignService;
    private IOrderItemService orderItemService;
    private RabbitTemplate rabbitTemplate;
    private StringRedisTemplate redisTemplate;
    private ThreadPoolExecutor threadPoolExecutor;
    private MemberFeignClient memberFeignClient;

    private BusinessNoGenerator businessNoGenerator;

    /**
     * ????????????
     */
    @Override
    public OrderConfirmVO confirm(OrderConfirmDTO orderConfirmDTO) {
        log.info("=======================????????????=======================\n?????????????????????{}", orderConfirmDTO);
        OrderConfirmVO orderConfirmVO = new OrderConfirmVO();
        Long memberId = RequestUtils.getUserId();
        // ????????????????????????
        CompletableFuture<Void> orderItemsCompletableFuture = CompletableFuture.runAsync(() -> {
            List<OrderItemDTO> orderItems = new ArrayList<>();
            if (orderConfirmDTO.getSkuId() != null) { // ????????????????????????
                OrderItemDTO orderItemDTO = OrderItemDTO.builder()
                        .skuId(orderConfirmDTO.getSkuId())
                        .count(orderConfirmDTO.getCount())
                        .build();
                SkuDTO sku = skuFeignService.getSkuById(orderConfirmDTO.getSkuId()).getData();
                orderItemDTO.setPrice(sku.getPrice());
                orderItemDTO.setPic(sku.getPic());
                orderItemDTO.setSkuName(sku.getName());
                orderItemDTO.setSkuCode(sku.getCode());
                orderItemDTO.setSpuName(sku.getSpuName());
                orderItems.add(orderItemDTO);
            } else { // ????????????????????????
                List<CartVO.CartItem> cartItems = cartService.getCartItems(memberId);
                List<OrderItemDTO> items = cartItems.stream()
                        .filter(CartVO.CartItem::getChecked)
                        .map(cartItem -> OrderItemDTO.builder()
                                .skuId(cartItem.getSkuId())
                                .count(cartItem.getCount())
                                .price(cartItem.getPrice())
                                .skuName(cartItem.getSkuName())
                                .skuCode(cartItem.getSkuCode())
                                .spuName(cartItem.getSpuName())
                                .pic(cartItem.getPic())
                                .build())
                        .collect(Collectors.toList());
                orderItems.addAll(items);
            }
            orderConfirmVO.setOrderItems(orderItems);
        }, threadPoolExecutor);

        // ????????????????????????
        CompletableFuture<Void> addressesCompletableFuture = CompletableFuture.runAsync(() -> {
            List<UmsAddress> addresses = addressFeignService.list(memberId).getData();
            orderConfirmVO.setAddresses(addresses);
        }, threadPoolExecutor);


        // ?????????????????????????????????????????????
        CompletableFuture<Void> orderTokenCompletableFuture = CompletableFuture.runAsync(() -> {
            String orderToken = businessNoGenerator.generate(BusinessTypeEnum.ORDER);
            orderConfirmVO.setOrderToken(orderToken);
            redisTemplate.opsForValue().set(ORDER_TOKEN_PREFIX + orderToken, orderToken);
        }, threadPoolExecutor);

        CompletableFuture.allOf(orderItemsCompletableFuture, addressesCompletableFuture, orderTokenCompletableFuture).join();
        log.info("?????????????????????{}", orderConfirmVO.toString());
        return orderConfirmVO;
    }

    /**
     * ????????????
     */
    @Override
    @GlobalTransactional
    public OrderSubmitVO submit(OrderSubmitDTO submitDTO) {
        log.info("=======================????????????=======================\n?????????????????????{}", submitDTO);
        // ????????????????????????
        String orderToken = submitDTO.getOrderToken();
        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(RELEASE_LOCK_LUA_SCRIPT, Long.class);
        Long result = this.redisTemplate.execute(redisScript, Collections.singletonList(ORDER_TOKEN_PREFIX + orderToken), orderToken);

        if (!ObjectUtil.equals(result, RELEASE_LOCK_SUCCESS_RESULT)) {
            throw new BizException("????????????????????????");
        }

        List<OrderItemDTO> orderItems = submitDTO.getOrderItems();
        if (CollectionUtil.isEmpty(orderItems)) {
            throw new BizException("?????????????????????????????????????????????");
        }

        // ????????????
        Long currentTotalPrice = orderItems.stream().map(item -> {
            SkuDTO sku = skuFeignService.getSkuById(item.getSkuId()).getData();
            if (sku != null) {
                return sku.getPrice() * item.getCount();
            }
            return 0l;
        }).reduce(0l, Long::sum);

        if (currentTotalPrice.compareTo(submitDTO.getTotalPrice()) != 0) {
            throw new BizException("????????????????????????????????????????????????");
        }

        // ????????????????????????????????????
        List<SkuLockDTO> skuLockList = orderItems.stream()
                .map(item -> SkuLockDTO.builder().skuId(item.getSkuId())
                        .count(item.getCount())
                        .orderToken(orderToken)
                        .build())
                .collect(Collectors.toList());

        Result lockResult = skuFeignService.lockStock(skuLockList);

        if (!Result.success().getCode().equals(lockResult.getCode())) {
            throw new BizException(Result.failed().getMsg());
        }

        // ????????????(??????????????????)
        OmsOrder order = new OmsOrder();
        order.setOrderSn(orderToken) // ???orderToken????????????????????????!???
                .setStatus(OrderStatusEnum.PENDING_PAYMENT.getCode())
                .setSourceType(OrderTypeEnum.APP.getCode())
                .setMemberId(RequestUtils.getUserId())
                .setRemark(submitDTO.getRemark())
                .setPayAmount(submitDTO.getPayAmount())
                .setTotalQuantity(orderItems.stream().map(item -> item.getCount()).reduce(0, (x, y) -> x + y))
                .setTotalAmount(orderItems.stream().map(item -> item.getPrice() * item.getCount()).reduce(0l, (x, y) -> x + y))
                .setGmtCreate(new Date());
        this.save(order);

        // ??????????????????
        List<OmsOrderItem> orderItemList = orderItems.stream().map(item -> OmsOrderItem.builder()
                .orderId(order.getId())
                .skuId(item.getSkuId())
                .skuName(item.getSkuName())
                .skuPrice(item.getPrice())
                .skuPic(item.getPic())
                .skuQuantity(item.getCount())
                .skuTotalPrice(item.getCount() * item.getPrice())
                .skuCode(item.getSkuCode())
                .build()).collect(Collectors.toList());
        orderItemService.saveBatch(orderItemList);

        // ?????????????????????????????????????????????????????????order.exchange?????????????????????????????????????????????
        log.info("??????????????????RabbitMQ?????????????????????SN???{}", orderToken);
        rabbitTemplate.convertAndSend("order.exchange", "order.create", orderToken);

        OrderSubmitVO submitVO = new OrderSubmitVO();
        submitVO.setOrderId(order.getId());
        submitVO.setOrderSn(order.getOrderSn());
        log.info("?????????????????????{}", submitVO.toString());
        return submitVO;
    }


    /**
     * ????????????
     *
     * @param orderId
     * @return
     */
    @Override
    @GlobalTransactional(rollbackFor = Exception.class)
    public boolean pay(Long orderId) {

        OmsOrder order = this.getById(orderId);
        if (order != null && !OrderStatusEnum.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BizException("????????????????????????????????????");
        }

        // ????????????
        Long userId = RequestUtils.getUserId();
        Long payAmount = order.getPayAmount();
        Result deductBalanceResult = memberFeignClient.deductBalance(userId, payAmount);
        if (!Result.isSuccess(deductBalanceResult)) {
            throw new BizException("????????????????????????");
        }

        // ????????????
        Result deductStockResult = skuFeignService.deductStock(order.getOrderSn());
        if (!Result.isSuccess(deductStockResult)) {
            throw new BizException("????????????????????????");
        }

        // ??????????????????
        order.setStatus(OrderStatusEnum.PAID.getCode());
        order.setPayType(PayTypeEnum.BALANCE.getCode());
        order.setPayTime(new Date());
        this.updateById(order);

        // ?????????????????????????????????????????????
        cartService.removeCheckedItem();

        return true;
    }

    @Override
    public boolean closeOrder(String orderToken) {
        log.info("=======================?????????????????????SN???{}=======================", orderToken);
        OmsOrder order = this.getOne(new LambdaQueryWrapper<OmsOrder>()
                .eq(OmsOrder::getOrderSn, orderToken));
        if (order == null || !OrderStatusEnum.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            return false;
        }
        order.setStatus(OrderStatusEnum.AUTO_CANCEL.getCode());
        return this.updateById(order);
    }

    @Override
    public boolean cancelOrder(Long id) {
        log.info("=======================?????????????????????ID???{}=======================", id);
        OmsOrder order = this.getById(id);

        if (order != null && !OrderStatusEnum.PENDING_PAYMENT.getCode().equals(order.getStatus())) {
            throw new BizException("??????????????????????????????????????????"); // ??????????????????????????????????????????????????????????????????????????????????????????
        }
        order.setStatus(OrderStatusEnum.USER_CANCEL.getCode());
        boolean result = this.updateById(order);
        if (result) {
            // ????????????????????????
            Result unlockResult = skuFeignService.unlockStock(order.getOrderSn());
            if (!Result.isSuccess(unlockResult)) {
                throw new BizException(unlockResult.getMsg());
            }
            result = true;
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteOrder(Long id) {
        log.info("=======================?????????????????????ID???{}=======================", id);
        OmsOrder order = this.getById(id);
        if (
                order != null &&
                        !OrderStatusEnum.AUTO_CANCEL.getCode().equals(order.getStatus()) &&
                        !OrderStatusEnum.USER_CANCEL.getCode().equals(order.getStatus())
        ) {
            throw new BizException("??????????????????????????????????????????????????????????????????");
        }
        return this.removeById(id);
    }


    @Override
    public IPage<OmsOrder> list(Page<OmsOrder> page, OmsOrder order) {
        List<OmsOrder> list = this.baseMapper.list(page, order);
        page.setRecords(list);
        return page;
    }

}
