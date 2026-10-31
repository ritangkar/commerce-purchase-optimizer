package com.purchaseoptimizer.occ.controller;

import de.hybris.platform.commercefacades.product.data.ProductData;
import de.hybris.platform.commercewebservicescommons.dto.product.ProductListWsDTO;
import de.hybris.platform.commercewebservicescommons.dto.product.ProductWsDTO;
import de.hybris.platform.webservicescommons.mapping.DataMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.purchaseoptimizer.exception.OptimizerException;
import com.purchaseoptimizer.facade.OptimizerFacade;


/**
 * Single OCC endpoint behind the "show my suggested cart" button.
 * GET /{baseSiteId}/users/{userId}/optimizer/suggested-cart?budgetTier=USUAL|HIGH&amp;fields=...
 * The dealer never supplies an amount; the order size is derived server-side from order history.
 */
@Controller
@RequestMapping(value = "/{baseSiteId}/users/{userId}/optimizer")
public class OptimizerController
{
	private static final String DEFAULT_FIELDS = "code,name,url,price(formattedValue,value,currencyIso),images(url,imageType,format),"
			+ "suggestedQuantity,suggestionReason,confidenceTier,suggestionGroup,explanationSource,evidenceLabels";

	@Resource(name = "optimizerFacade")
	private OptimizerFacade optimizerFacade;

	@Resource(name = "dataMapper")
	private DataMapper dataMapper;

	@Secured({ "ROLE_CUSTOMERGROUP", "ROLE_TRUSTED_CLIENT" })
	@RequestMapping(value = "/suggested-cart", method = RequestMethod.GET)
	@ResponseBody
	public ProductListWsDTO getSuggestedCart(@PathVariable final String userId,
			@RequestParam(required = false, defaultValue = "USUAL") final String budgetTier,
			@RequestParam(required = false, defaultValue = DEFAULT_FIELDS) final String fields, final HttpServletRequest request)
	{
		final List<ProductData> data = optimizerFacade.getSuggestedCart(userId, budgetTier, request.getParameterMap().keySet());
		final List<ProductWsDTO> products = dataMapper.mapAsList(data, ProductWsDTO.class, fields);
		final ProductListWsDTO dto = new ProductListWsDTO();
		dto.setProducts(products);
		return dto;
	}

	@ExceptionHandler(OptimizerException.class)
	public ResponseEntity<Map<String, Object>> handleOptimizerException(final OptimizerException e)
	{
		final HttpStatus status;
		switch (e.getReason())
		{
			case ACCESS_DENIED:
				status = HttpStatus.FORBIDDEN;
				break;
			case DISABLED:
				status = HttpStatus.NOT_FOUND;
				break;
			case NO_B2B_UNIT:
			case BAD_REQUEST:
			default:
				status = HttpStatus.BAD_REQUEST;
		}
		final Map<String, Object> error = new HashMap<>();
		error.put("type", e.getReason().name());
		error.put("message", e.getMessage());
		return new ResponseEntity<>(Map.of("errors", java.util.List.of(error)), status);
	}
}
