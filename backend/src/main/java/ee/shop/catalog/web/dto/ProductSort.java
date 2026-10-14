package ee.shop.catalog.web.dto;

/** RELEVANCE only means anything when a search term (q) is present. */
public enum ProductSort { RELEVANCE, PRICE_ASC, PRICE_DESC, RATING_DESC }
