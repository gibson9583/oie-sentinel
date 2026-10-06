import React from 'react';
import { createRoot } from 'react-dom/client';
const state = new Map();
export const control = window.control = { permission: true, calls: [], pending: [], confirms: [], allowLeave: false, modal: null };
export const platform = {
    React, ReactDOM: { createRoot },
    checkTask: () => control.permission,
    ui: { h: () => document.createElement('span'), toast() {}, fmtNumber: String, fmtDate: String },
    store: { getState: (k) => state.get(k), setState: (k,v) => state.set(k,v), subscribe: () => () => {} },
    api: {
        get: async () => [],
        post: (path, payload) => { control.calls.push({ path, payload }); return new Promise((resolve, reject) => control.pending.push({resolve,reject})); },
        put: (path, payload) => { control.calls.push({ path, payload }); return Promise.resolve(payload); },
        del: async () => ({}),
    },
};
export const confirmDialog = async (...args) => { control.confirms.push(args); return control.allowLeave; };
export const errorModal = (...args) => { control.modal = args; };
export const detailModal = (args) => { control.modal = args; };
export class DataTable { constructor() { this.el = document.createElement('div'); } setRows() {} }
export const promptDialog = async () => null;
export const fmtDate = String;
export const fmtNumber = String;
