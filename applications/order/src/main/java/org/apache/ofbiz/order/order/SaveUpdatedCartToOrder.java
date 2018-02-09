package org.apache.ofbiz.order.order;

import org.apache.ofbiz.base.util.*;
import org.apache.ofbiz.common.DataModelConstants;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.order.shoppingcart.CheckOutHelper;
import org.apache.ofbiz.order.shoppingcart.ShoppingCart;
import org.apache.ofbiz.order.shoppingcart.product.ProductPromoWorker;
import org.apache.ofbiz.order.shoppingcart.shipping.ShippingEvents;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ModelService;
import org.apache.ofbiz.service.ServiceUtil;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.util.*;

public class SaveUpdatedCartToOrder {

	private final String module;
	private final LocalDispatcher dispatcher;
	private final Delegator delegator;
	private final ShoppingCart cart;
	private final Locale locale;
	private final GenericValue userLogin;
	private final String orderId;
	private final Map<String, Object> changeMap;
	private final boolean calcTax;
	private final boolean deleteItems;

	public SaveUpdatedCartToOrder(String module, LocalDispatcher dispatcher, Delegator delegator, ShoppingCart cart,
								  Locale locale, GenericValue userLogin, String orderId, Map<String, Object> changeMap, boolean calcTax,
								  boolean deleteItems) throws GeneralException {
		this.module = module;
		this.dispatcher = dispatcher;
		this.delegator = delegator;
		this.cart = cart;
		this.locale = locale;
		this.userLogin = userLogin;
		this.orderId = orderId;
		this.changeMap = changeMap;
		this.calcTax = calcTax;
		this.deleteItems = deleteItems;
	}

	public void execute() throws GeneralException {
		// get/set the shipping estimates. If it's a SALES ORDER, then return an error if there are no ship estimates
		int shipGroupsSize = cart.getShipGroupSize();
		int realShipGroupsSize = (new OrderReadHelper(delegator, orderId)).getOrderItemShipGroups().size();
		// If an empty csi has initially been added to cart.shipInfo by ShoppingCart.setItemShipGroupQty() (called indirectly by ShoppingCart.setUserLogin() and then ProductPromoWorker.doPromotions(), etc.)
		//  shipGroupsSize > realShipGroupsSize are different (+1 for shipGroupsSize), then simply bypass the 1st empty csi!
		int origin = realShipGroupsSize == shipGroupsSize ? 0 : 1;
		for (int gi = origin; gi < shipGroupsSize; gi++) {
			String shipmentMethodTypeId = cart.getShipmentMethodTypeId(gi);
			String carrierPartyId = cart.getCarrierPartyId(gi);
			Debug.logInfo("Getting ship estimate for group #" + gi + " [" + shipmentMethodTypeId + " / " + carrierPartyId + "]", module);
			Map<String, Object> result = ShippingEvents.getShipGroupEstimate(dispatcher, delegator, cart, gi);
			if (("SALES_ORDER".equals(cart.getOrderType())) && (ServiceUtil.isError(result))) {
				Debug.logError(ServiceUtil.getErrorMessage(result), module);
				throw new GeneralException(ServiceUtil.getErrorMessage(result));
			}

			BigDecimal shippingTotal = (BigDecimal) result.get("shippingTotal");
			if (shippingTotal == null) {
				shippingTotal = BigDecimal.ZERO;
			}
			cart.setItemShipGroupEstimate(shippingTotal, gi);
		}

		// calc the sales tax
		CheckOutHelper coh = new CheckOutHelper(dispatcher, delegator, cart);
		if (calcTax) {
			try {
				coh.calcAndAddTax();
			} catch (GeneralException e) {
				Debug.logError(e, module);
				throw new GeneralException(e.getMessage());
			}
		}

		// get the new orderItems, adjustments, shipping info, payments and order item attributes from the cart
		List<Map<String, Object>> modifiedItems = new LinkedList<Map<String,Object>>();
		List<Map<String, Object>> newItems = new LinkedList<Map<String,Object>>();
		List<GenericValue> toStore = new LinkedList<GenericValue>();
		List<GenericValue> toAddList = new ArrayList<GenericValue>();
		toAddList.addAll(cart.makeAllAdjustments());
		cart.clearAllPromotionAdjustments();
		ProductPromoWorker.doPromotions(cart, dispatcher);

		// validate the payment methods
		Map<String, Object> validateResp = coh.validatePaymentMethods();
		if (ServiceUtil.isError(validateResp)) {
			throw new GeneralException(ServiceUtil.getErrorMessage(validateResp));
		}

		// handle OrderHeader fields
		String billingAccountId = cart.getBillingAccountId();
		if (UtilValidate.isNotEmpty(billingAccountId)) {
			try {
				GenericValue orderHeader = EntityQuery.use(delegator).from("OrderHeader").where("orderId", orderId).queryOne();
				orderHeader.set("billingAccountId", billingAccountId);
				toStore.add(orderHeader);
			} catch (GenericEntityException e) {
				Debug.logError(e, module);
				throw new GeneralException(e.getMessage());
			}
		}

		toStore.addAll(cart.makeOrderItems(dispatcher));
		toStore.addAll(cart.makeAllAdjustments());

		long groupIndex = cart.getShipInfoSize();
		if (!deleteItems) {
			for (long itr = 1; itr <= groupIndex; itr++) {
				List<GenericValue> removeList = new ArrayList<GenericValue>();
				for (GenericValue stored: toStore) {
					if ("OrderAdjustment".equals(stored.getEntityName())) {
						if (("SHIPPING_CHARGES".equals(stored.get("orderAdjustmentTypeId")) ||
								"SALES_TAX".equals(stored.get("orderAdjustmentTypeId"))) &&
								stored.get("orderId").equals(orderId)) {
							// Removing objects from toStore list for old Shipping and Handling Charges Adjustment and Sales Tax Adjustment.
							removeList.add(stored);
						}
						if ("Y".equals(stored.getString("isManual"))) {
							// Removing objects from toStore list for Manually added Adjustment.
							removeList.add(stored);
						}
					}
				}
				toStore.removeAll(removeList);
			}
			for (GenericValue toAdd: toAddList) {
				if ("OrderAdjustment".equals(toAdd.getEntityName())) {
					if ("Y".equals(toAdd.getString("isManual")) && (("PROMOTION_ADJUSTMENT".equals(toAdd.get("orderAdjustmentTypeId"))) ||
							("SHIPPING_CHARGES".equals(toAdd.get("orderAdjustmentTypeId"))) || ("SALES_TAX".equals(toAdd.get("orderAdjustmentTypeId"))))) {
						toStore.add(toAdd);
					}
				}
			}
		} else {
			// add all the cart adjustments
			toStore.addAll(toAddList);
		}

		// Creating objects for New Shipping and Handling Charges Adjustment and Sales Tax Adjustment
		toStore.addAll(cart.makeAllShipGroupInfos());
		toStore.addAll(cart.makeAllOrderPaymentInfos(dispatcher));
		toStore.addAll(cart.makeAllOrderItemAttributes(orderId, ShoppingCart.FILLED_ONLY));

		List<GenericValue> toRemove = new LinkedList<GenericValue>();
		if (deleteItems) {
			// flag to delete existing order items and adjustments
			try {
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemShipGroupAssoc").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemContactMech").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemPriceInfo").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemAttribute").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemBilling").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemRole").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItemChange").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderAdjustment").where("orderId", orderId).queryList());
				toRemove.addAll(EntityQuery.use(delegator).from("OrderItem").where("orderId", orderId).queryList());
			} catch (GenericEntityException e) {
				Debug.logError(e, module);
			}
		} else {
			// get the empty order item atrributes from the cart and remove them
			toRemove.addAll(cart.makeAllOrderItemAttributes(orderId, ShoppingCart.EMPTY_ONLY));
		}

		// get the promo uses and codes
		for (String promoCodeEntered : cart.getProductPromoCodesEntered()) {
			GenericValue orderProductPromoCode = delegator.makeValue("OrderProductPromoCode");
			orderProductPromoCode.set("orderId", orderId);
			orderProductPromoCode.set("productPromoCodeId", promoCodeEntered);
			toStore.add(orderProductPromoCode);
		}
		for (GenericValue promoUse : cart.makeProductPromoUses()) {
			promoUse.set("orderId", orderId);
			toStore.add(promoUse);
		}

		List<GenericValue> existingPromoCodes = null;
		List<GenericValue> existingPromoUses = null;
		try {
			existingPromoCodes = EntityQuery.use(delegator).from("OrderProductPromoCode").where("orderId", orderId).queryList();
			existingPromoUses = EntityQuery.use(delegator).from("ProductPromoUse").where("orderId", orderId).queryList();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
		}
		toRemove.addAll(existingPromoCodes);
		toRemove.addAll(existingPromoUses);

		// set the orderId & other information on all new value objects
		List<String> dropShipGroupIds = new LinkedList<String>(); // this list will contain the ids of all the ship groups for drop shipments (no reservations)
		for (GenericValue valueObj : toStore) {
			valueObj.set("orderId", orderId);
			if ("OrderItemShipGroup".equals(valueObj.getEntityName())) {
				// ship group
				if (valueObj.get("carrierRoleTypeId") == null) {
					valueObj.set("carrierRoleTypeId", "CARRIER");
				}
				if (UtilValidate.isNotEmpty(valueObj.get("supplierPartyId"))) {
					dropShipGroupIds.add(valueObj.getString("shipGroupSeqId"));
				}
			} else if ("OrderAdjustment".equals(valueObj.getEntityName())) {
				// shipping / tax adjustment(s)
				if (UtilValidate.isEmpty(valueObj.get("orderItemSeqId"))) {
					valueObj.set("orderItemSeqId", DataModelConstants.SEQ_ID_NA);
				}
				// in order to avoid duplicate adjustments don't set orderAdjustmentId (which is the pk) if there is already one
				if (UtilValidate.isEmpty(valueObj.getString("orderAdjustmentId"))) {
					valueObj.set("orderAdjustmentId", delegator.getNextSeqId("OrderAdjustment"));
				}
				valueObj.set("createdDate", UtilDateTime.nowTimestamp());
				valueObj.set("createdByUserLogin", userLogin.getString("userLoginId"));
			} else if ("OrderPaymentPreference".equals(valueObj.getEntityName())) {
				if (valueObj.get("orderPaymentPreferenceId") == null) {
					valueObj.set("orderPaymentPreferenceId", delegator.getNextSeqId("OrderPaymentPreference"));
					valueObj.set("createdDate", UtilDateTime.nowTimestamp());
					valueObj.set("createdByUserLogin", userLogin.getString("userLoginId"));
				}
				if (valueObj.get("statusId") == null) {
					valueObj.set("statusId", "PAYMENT_NOT_RECEIVED");
				}
			} else if ("OrderItem".equals(valueObj.getEntityName()) && !deleteItems) {

				//  ignore promotion items. They are added/canceled automatically
				if ("Y".equals(valueObj.getString("isPromo"))) {
					//Fetching the new promo items and adding it to list so that we can create OrderStatus record for that items.
					Map<String, Object> promoItem = new HashMap<String, Object>();
					promoItem.put("orderId", valueObj.getString("orderId"));
					promoItem.put("orderItemSeqId", valueObj.getString("orderItemSeqId"));
					promoItem.put("quantity", valueObj.getBigDecimal("quantity"));
					newItems.add(promoItem);
					continue;
				}
				GenericValue oldOrderItem = null;
				try {
					oldOrderItem = EntityQuery.use(delegator).from("OrderItem").where("orderId", valueObj.getString("orderId"), "orderItemSeqId", valueObj.getString("orderItemSeqId")).queryOne();
				} catch (GenericEntityException e) {
					Debug.logError(e, module);
					throw new GeneralException(e.getMessage());
				}
				if (oldOrderItem != null) {

					//  Existing order item found. Check for modifications and store if any
					String oldItemDescription = oldOrderItem.getString("itemDescription") != null ? oldOrderItem.getString("itemDescription") : "";
					BigDecimal oldQuantity = oldOrderItem.getBigDecimal("quantity") != null ? oldOrderItem.getBigDecimal("quantity") : BigDecimal.ZERO;
					BigDecimal oldUnitPrice = oldOrderItem.getBigDecimal("unitPrice") != null ? oldOrderItem.getBigDecimal("unitPrice") : BigDecimal.ZERO;
					String oldItemComment = oldOrderItem.getString("comments") != null ? oldOrderItem.getString("comments") : "";

					boolean changeFound = false;
					Map<String, Object> modifiedItem = new HashMap<String, Object>();
					if (!oldItemDescription.equals(valueObj.getString("itemDescription"))) {
						modifiedItem.put("itemDescription", oldItemDescription);
						changeFound = true;
					}

					if (!oldItemComment.equals(valueObj.getString("comments"))) {
						modifiedItem.put("changeComments", valueObj.getString("comments"));
						changeFound = true;
					}

					BigDecimal quantityDif = valueObj.getBigDecimal("quantity").subtract(oldQuantity);
					BigDecimal unitPriceDif = valueObj.getBigDecimal("unitPrice").subtract(oldUnitPrice);
					if (quantityDif.compareTo(BigDecimal.ZERO) != 0) {
						modifiedItem.put("quantity", quantityDif);
						changeFound = true;
					}
					if (unitPriceDif.compareTo(BigDecimal.ZERO) != 0) {
						modifiedItem.put("unitPrice", unitPriceDif);
						changeFound = true;
					}
					if (changeFound) {

						//  found changes to store
						Map<String, String> itemReasonMap = UtilGenerics.checkMap(changeMap.get("itemReasonMap"));
						if (UtilValidate.isNotEmpty(itemReasonMap)) {
							String changeReasonId = itemReasonMap.get(valueObj.getString("orderItemSeqId"));
							modifiedItem.put("reasonEnumId", changeReasonId);
						}

						modifiedItem.put("orderId", valueObj.getString("orderId"));
						modifiedItem.put("orderItemSeqId", valueObj.getString("orderItemSeqId"));
						modifiedItem.put("changeTypeEnumId", "ODR_ITM_UPDATE");
						modifiedItems.add(modifiedItem);
					}
				} else {

					//  this is a new item appended to the order
					Map<String, String> itemReasonMap = UtilGenerics.checkMap(changeMap.get("itemReasonMap"));
					Map<String, String> itemCommentMap = UtilGenerics.checkMap(changeMap.get("itemCommentMap"));
					Map<String, Object> appendedItem = new HashMap<String, Object>();
					if (UtilValidate.isNotEmpty(itemReasonMap)) {
						String changeReasonId = itemReasonMap.get("reasonEnumId");
						appendedItem.put("reasonEnumId", changeReasonId);
					}
					if (UtilValidate.isNotEmpty(itemCommentMap)) {
						String changeComments = itemCommentMap.get("changeComments");
						appendedItem.put("changeComments", changeComments);
					}

					appendedItem.put("orderId", valueObj.getString("orderId"));
					appendedItem.put("orderItemSeqId", valueObj.getString("orderItemSeqId"));
					appendedItem.put("quantity", valueObj.getBigDecimal("quantity"));
					appendedItem.put("changeTypeEnumId", "ODR_ITM_APPEND");
					modifiedItems.add(appendedItem);
					newItems.add(appendedItem);
				}
			}
		}

		if (Debug.verboseOn())
			Debug.logVerbose("To Store Contains: " + toStore, module);

		// remove any order item attributes that were set to empty
		try {
			delegator.removeAll(toRemove);
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			throw new GeneralException(e.getMessage());
		}

		// store the new items/adjustments/order item attributes
		try {
			delegator.storeAll(toStore);
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			throw new GeneralException(e.getMessage());
		}

		//  store the OrderItemChange
		if (UtilValidate.isNotEmpty(modifiedItems)) {
			for (Map<String, Object> modifiendItem: modifiedItems) {
				Map<String, Object> serviceCtx = new HashMap<String, Object>();
				serviceCtx.put("orderId", modifiendItem.get("orderId"));
				serviceCtx.put("orderItemSeqId", modifiendItem.get("orderItemSeqId"));
				serviceCtx.put("itemDescription", modifiendItem.get("itemDescription"));
				serviceCtx.put("quantity", modifiendItem.get("quantity"));
				serviceCtx.put("unitPrice", modifiendItem.get("unitPrice"));
				serviceCtx.put("changeTypeEnumId", modifiendItem.get("changeTypeEnumId"));
				serviceCtx.put("reasonEnumId", modifiendItem.get("reasonEnumId"));
				serviceCtx.put("changeComments", modifiendItem.get("changeComments"));
				serviceCtx.put("userLogin", userLogin);
				Map<String, Object> resp = null;
				try {
					resp = dispatcher.runSync("createOrderItemChange", serviceCtx);
				} catch (GenericServiceException e) {
					Debug.logError(e, module);
					throw new GeneralException(e.getMessage());
				}
				if (ServiceUtil.isError(resp)) {
					throw new GeneralException((String) resp.get(ModelService.ERROR_MESSAGE));
				}
			}
		}

		//To create record of OrderStatus entity
		if (UtilValidate.isNotEmpty(newItems)) {
			for (Map<String, Object> newItem : newItems) {
				String itemStatusId = delegator.getNextSeqId("OrderStatus");
				GenericValue itemStatus = delegator.makeValue("OrderStatus", UtilMisc.toMap("orderStatusId", itemStatusId));
				itemStatus.put("statusId", "ITEM_CREATED");
				itemStatus.put("orderId", newItem.get("orderId"));
				itemStatus.put("orderItemSeqId", newItem.get("orderItemSeqId"));
				itemStatus.put("statusDatetime", UtilDateTime.nowTimestamp());
				itemStatus.set("statusUserLogin", userLogin.get("userLoginId"));
				delegator.create(itemStatus);
			}
		}

		// make the order item object map & the ship group assoc list
		List<GenericValue> orderItemShipGroupAssoc = new LinkedList<GenericValue>();
		Map<String, GenericValue> itemValuesBySeqId = new HashMap<String, GenericValue>();
		for (GenericValue v : toStore) {
			if ("OrderItem".equals(v.getEntityName())) {
				itemValuesBySeqId.put(v.getString("orderItemSeqId"), v);
			} else if ("OrderItemShipGroupAssoc".equals(v.getEntityName())) {
				orderItemShipGroupAssoc.add(v);
			}
		}

		// reserve the inventory
		String productStoreId = cart.getProductStoreId();
		String orderTypeId = cart.getOrderType();
		List<String> resErrorMessages = new LinkedList<String>();
		try {
			Debug.logInfo("Calling reserve inventory...", module);
			OrderServices.reserveInventory(delegator, dispatcher, userLogin, locale, orderItemShipGroupAssoc, dropShipGroupIds, itemValuesBySeqId,
					orderTypeId, productStoreId, resErrorMessages);
		} catch (GeneralException e) {
			Debug.logError(e, module);
			throw new GeneralException(e.getMessage());
		}

		if (resErrorMessages.size() > 0) {
			throw new GeneralException(ServiceUtil.getErrorMessage(ServiceUtil.returnError(resErrorMessages)));
		}
	}
}
