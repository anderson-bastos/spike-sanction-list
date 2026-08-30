package com.spike.ofac.pipeline.stages

/**
 * The six source-independent pipeline stages:
 *   obtain -> validate -> transform -> version -> persist -> publish
 *
 * Each stage is a language-neutral contract in the design; the concrete
 * implementations arrive in tasks 3-15. Any stage failing before atomic
 * activation leaves CURRENT unchanged (Req 11).
 *
 * Task 1 only declares the package for the layout.
 */
